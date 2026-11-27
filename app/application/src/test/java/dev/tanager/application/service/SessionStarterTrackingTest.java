package dev.tanager.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tanager.application.port.in.AppEvent;
import dev.tanager.application.port.in.StartFailure;
import dev.tanager.application.port.in.StartNotice;
import dev.tanager.application.port.in.StartRequest;
import dev.tanager.application.port.in.StartedSession;
import dev.tanager.application.port.out.FakePlatformModule;
import dev.tanager.application.port.out.FakeShellIntegration;
import dev.tanager.application.port.out.InMemoryConfigRepository;
import dev.tanager.application.port.out.LaunchError;
import dev.tanager.application.port.out.ScriptedPtyLauncher;
import dev.tanager.application.port.out.ScriptedTerminalStream;
import dev.tanager.application.port.out.TerminalSize;
import dev.tanager.application.state.AppState;
import dev.tanager.application.state.FakeClock;
import dev.tanager.application.state.ManualExecutor;
import dev.tanager.application.state.StateActor;
import dev.tanager.domain.history.HistoryPolicy;
import dev.tanager.domain.history.SessionLeaf;
import dev.tanager.domain.history.SessionStatus;
import dev.tanager.domain.history.ShellEnvironment;
import dev.tanager.domain.history.WorkHistory;
import dev.tanager.domain.history.WorkspacePath;
import dev.tanager.domain.session.IntegrationMode;
import dev.tanager.domain.session.LaunchTemplate;
import dev.tanager.domain.session.ProfileId;
import dev.tanager.domain.session.ShellKind;
import dev.tanager.domain.session.ShellProfile;
import dev.tanager.domain.session.TerminalSession;
import dev.tanager.domain.session.WorkingDir;
import dev.tanager.domain.shared.PaneId;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 세션 시작 → 구독 → OSC → 방문 기록 → 세션 cwd → 이벤트 (FR-HIS-01 · FR-SES-03). 액터는 호출 스레드에서 바로 돈다. */
class SessionStarterTrackingTest {
    private static final ShellEnvironment UBUNTU = new ShellEnvironment.Wsl("Ubuntu");
    private static final ShellEnvironment WIN = new ShellEnvironment.Windows();
    private static final ShellProfile BASH = profile("wsl:Ubuntu", ShellKind.BASH, UBUNTU, IntegrationMode.OSC7);
    private static final ShellProfile POWERSHELL =
            profile("powershell", ShellKind.WINDOWS_POWERSHELL, WIN, IntegrationMode.OSC9_9);
    private static final ShellProfile CMD_WITHOUT_INTEGRATION =
            profile("cmd", ShellKind.CMD, WIN, IntegrationMode.NONE);
    private static final TerminalSize SIZE = new TerminalSize(100, 30);
    private static final PaneId P1 = new PaneId("p1");
    private static final PaneId P2 = new PaneId("p2");

    private final FakePlatformModule platform =
            new FakePlatformModule(List.of(BASH, POWERSHELL, CMD_WITHOUT_INTEGRATION));
    private final ScriptedPtyLauncher launcher = new ScriptedPtyLauncher();
    private final InMemoryConfigRepository config = new InMemoryConfigRepository();
    private final StateActor state =
            new StateActor(AppState.initial(WorkHistory.empty(HistoryPolicy.DEFAULT)), Runnable::run);
    private final List<AppEvent> events = eventsOf(state);
    private final SessionStarter starter = starterOn(state);

    private static List<AppEvent> eventsOf(StateActor actor) {
        List<AppEvent> received = new ArrayList<>();
        actor.subscribe(received::add);
        return received;
    }

    private SessionStarter starterOn(StateActor actor) {
        return new SessionStarter(
                new ProfileCatalog(platform),
                config,
                platform,
                new IntegrationStatus(platform),
                launcher,
                new DefaultProfileMemory(config),
                actor,
                new VisitRecorder(actor, FakeClock.atEpoch()));
    }

    private static ShellProfile profile(String id, ShellKind kind, ShellEnvironment env, IntegrationMode mode) {
        return new ShellProfile(new ProfileId(id), id, kind, env, new LaunchTemplate(id + ".exe", List.of()), mode);
    }

    private static String osc7(String path) {
        return "\u001b]7;file://PC" + path + "\u0007";
    }

    private static String osc99(String path) {
        return "\u001b]9;9;\"" + path + "\"\u0007";
    }

    private static WorkspacePath wsl(String raw) {
        return WorkspacePath.of(UBUNTU, raw).value().orElseThrow();
    }

    private static WorkspacePath win(String raw) {
        return WorkspacePath.of(WIN, raw).value().orElseThrow();
    }

    private StartedSession start(PaneId pane, ShellProfile profile, Optional<WorkingDir> dir) {
        return starter.start(new StartRequest(pane, Optional.of(profile.id()), false, dir, SIZE))
                .value()
                .orElseThrow();
    }

    private StartedSession startAtHome(PaneId pane, ShellProfile profile) {
        return start(pane, profile, Optional.empty());
    }

    private int count(WorkspacePath path) {
        return state.snapshot().history().find(path).map(v -> v.visitCount()).orElse(0);
    }

    private SessionLeaf leaf(PaneId pane) {
        return state.snapshot().sessions().leaves().stream()
                .filter(l -> l.pane().equals(pane))
                .findFirst()
                .orElseThrow();
    }

    private ScriptedTerminalStream streamOf(int index) {
        return launcher.streams().get(index);
    }

    @Test
    @DisplayName("홈에서 띄운 WSL 세션이 OSC 7 을 내면 이력에 기록되고 세션 경로가 바뀌고 이벤트가 나간다")
    void osc7RecordsVisitAndUpdatesSession() {
        // given
        launcher.willScript(s -> s.emit(osc7("/home/me")));
        StartedSession session = startAtHome(P1, BASH);
        events.clear();

        // when
        session.stream().start();

        // then
        assertThat(count(wsl("/home/me"))).isEqualTo(1);
        assertThat(leaf(P1).cwd()).contains(wsl("/home/me"));
        assertThat(events).contains(new AppEvent.SessionCwdChanged(P1, wsl("/home/me")));
    }

    @Test
    @DisplayName("구독은 start 가 돌려주기 전에 붙어 있고, UI 가 stream.start() 할 때 첫 바이트의 OSC 도 놓치지 않는다")
    void subscriptionIsAttachedBeforeStreamStarts() {
        // given: 스크립트가 시작 직후 첫 출력에 OSC 를 낸다(start() 전에 쌓인 출력은 그때 붙어 있던 구독자만 받는다)
        launcher.willScript(s -> s.emit(osc7("/first")));

        // when
        StartedSession session = startAtHome(P1, BASH);

        // then
        assertThat(streamOf(0).subscriberCount()).isEqualTo(1);
        assertThat(count(wsl("/first"))).isZero();
        session.stream().start();
        assertThat(count(wsl("/first"))).isEqualTo(1);
    }

    @Test
    @DisplayName("시작 경로와 같은 경로를 첫 OSC 가 알려도 기록된다")
    void firstReportEqualToStartDirIsRecorded() {
        // given
        launcher.willScript(s -> s.emit(osc7("/a")));
        StartedSession session = start(P1, BASH, Optional.of(new WorkingDir.At(wsl("/a"))));

        // when
        session.stream().start();

        // then
        assertThat(count(wsl("/a"))).isEqualTo(1);
    }

    @Test
    @DisplayName("프롬프트마다 같은 경로가 와도 횟수는 그대로이고 a → b → a 는 a 가 2회다")
    void repeatedAndAlternatingReports() {
        // given
        StartedSession session = startAtHome(P1, BASH);
        session.stream().start();
        ScriptedTerminalStream stream = streamOf(0);

        // when
        stream.emit(osc7("/a"));
        stream.emit(osc7("/a"));
        stream.emit(osc7("/a"));
        assertThat(count(wsl("/a"))).isEqualTo(1);
        stream.emit(osc7("/b"));
        stream.emit(osc7("/a"));

        // then
        assertThat(count(wsl("/a"))).isEqualTo(2);
        assertThat(count(wsl("/b"))).isEqualTo(1);
    }

    @Test
    @DisplayName("두 창이 같은 경로를 알리면 2회이고 창마다 번호를 받는다")
    void twoPanesCountSeparately() {
        // given
        startAtHome(P1, BASH).stream().start();
        startAtHome(P2, BASH).stream().start();

        // when
        streamOf(0).emit(osc7("/a"));
        streamOf(1).emit(osc7("/a"));

        // then
        assertThat(count(wsl("/a"))).isEqualTo(2);
        assertThat(leaf(P1).number()).isEqualTo(1);
        assertThat(leaf(P2).number()).isEqualTo(2);
    }

    @Test
    @DisplayName("띄운 세션은 Running 으로 등록되고 SessionsChanged 가 나간다")
    void launchRegistersRunningSession() {
        // when
        startAtHome(P1, BASH);

        // then
        assertThat(leaf(P1).status()).isEqualTo(new SessionStatus.Running());
        assertThat(leaf(P1).shellName()).isEqualTo("wsl:Ubuntu");
        assertThat(events).anyMatch(AppEvent.SessionsChanged.class::isInstance);
    }

    @Test
    @DisplayName("PowerShell(OSC9_9) 세션은 OSC 9;9 를 기록하고 OSC 7 은 무시한다")
    void powershellRecordsOnlyOsc99() {
        // given
        startAtHome(P1, POWERSHELL).stream().start();

        // when
        streamOf(0).emit(osc7("/home/me"));
        streamOf(0).emit(osc99("C:\\work"));

        // then
        assertThat(count(win("C:\\work"))).isEqualTo(1);
        assertThat(state.snapshot().history().entries()).hasSize(1);
    }

    @Test
    @DisplayName("셸 통합을 쓸 수 없으면 구독 없이 시작 경로를 한 번 기록하고 알림을 준다")
    void unavailableIntegrationRecordsStartDirOnce() {
        // given
        platform.withIntegration(FakeShellIntegration.unavailable("스크립트를 쓸 수 없다"));

        // when
        StartedSession session = start(P1, POWERSHELL, Optional.of(new WorkingDir.At(win("C:\\work"))));

        // then
        assertThat(count(win("C:\\work"))).isEqualTo(1);
        assertThat(streamOf(0).subscriberCount()).isZero();
        assertThat(session.notices()).containsExactly(new StartNotice.IntegrationUnavailable("스크립트를 쓸 수 없다"));
        assertThat(session.notices().get(0).describe()).isNotBlank();
        assertThat(leaf(P1).cwd()).contains(win("C:\\work"));
    }

    @Test
    @DisplayName("셸 통합을 쓸 수 없고 홈에서 시작하면 기록은 없고 알림만 있다")
    void unavailableIntegrationAtHomeRecordsNothing() {
        // given
        platform.withIntegration(FakeShellIntegration.unavailable("x"));

        // when
        StartedSession session = startAtHome(P1, POWERSHELL);

        // then
        assertThat(state.snapshot().history().entries()).isEmpty();
        assertThat(session.notices()).containsExactly(new StartNotice.IntegrationUnavailable("x"));
    }

    @Test
    @DisplayName("통합 모드가 NONE 인 프로파일은 알림 없이 구독도 하지 않는다")
    void noneModeProfileIsNotTracked() {
        // when
        StartedSession session = startAtHome(P1, CMD_WITHOUT_INTEGRATION);

        // then
        assertThat(streamOf(0).subscriberCount()).isZero();
        assertThat(session.notices()).isEmpty();
    }

    @Test
    @DisplayName("셸이 끝나면 세션은 Exited 로 마지막 경로를 유지하고 SessionsChanged 가 나간다")
    void exitMarksSessionExitedKeepingLastCwd() {
        // given
        startAtHome(P1, BASH).stream().start();
        streamOf(0).emit(osc7("/a"));
        events.clear();

        // when
        streamOf(0).exit(3);

        // then
        assertThat(leaf(P1).status()).isEqualTo(new SessionStatus.Exited(3));
        assertThat(leaf(P1).cwd()).contains(wsl("/a"));
        assertThat(events).anyMatch(AppEvent.SessionsChanged.class::isInstance);
    }

    @Test
    @DisplayName("재시작하면 번호가 유지되고 마지막으로 알린 경로가 비워져 첫 보고가 다시 기록된다")
    void restartKeepsNumberAndRecordsFirstReportAgain() {
        // given
        StartedSession first = startAtHome(P1, BASH);
        first.stream().start();
        streamOf(0).emit(osc7("/a"));
        streamOf(0).exit(0);
        TerminalSession exited = first.exited().toCompletableFuture().join();

        // when
        StartedSession second = starter.restart(P1, exited, SIZE).value().orElseThrow();
        second.stream().start();
        streamOf(1).emit(osc7("/a"));

        // then
        assertThat(leaf(P1).number()).isEqualTo(1);
        assertThat(leaf(P1).status()).isEqualTo(new SessionStatus.Running());
        assertThat(count(wsl("/a"))).isEqualTo(2);
    }

    @Test
    @DisplayName("재시작은 처음 시작한 경로가 아니라 셸이 마지막으로 알린 경로에서 띄운다")
    void restartStartsAtLastReportedPath() {
        // given: /a 에서 시작해 셸이 /b 를 알린 뒤 끝났다. UI 가 가진 exited 는 처음 Running 에서 만들어 /a 를 가리킨다
        StartedSession first = start(P1, BASH, Optional.of(new WorkingDir.At(wsl("/a"))));
        first.stream().start();
        streamOf(0).emit(osc7("/b"));
        streamOf(0).exit(0);
        TerminalSession exited = first.exited().toCompletableFuture().join();

        // when
        StartedSession second = starter.restart(P1, exited, SIZE).value().orElseThrow();

        // then
        assertThat(platform.launchRequests().getLast())
                .isEqualTo(new FakePlatformModule.LaunchRequest(BASH, new WorkingDir.At(wsl("/b"))));
        assertThat(second.session().knownCwd()).contains(wsl("/b"));
    }

    @Test
    @DisplayName("셸이 경로를 알린 적이 없으면 재시작은 처음 시작한 경로에서 띄운다")
    void restartWithoutReportsStartsAtOriginalPath() {
        // given
        StartedSession first = start(P1, BASH, Optional.of(new WorkingDir.At(wsl("/a"))));
        first.stream().start();
        streamOf(0).exit(0);
        TerminalSession exited = first.exited().toCompletableFuture().join();

        // when
        starter.restart(P1, exited, SIZE).value().orElseThrow();

        // then
        assertThat(platform.launchRequests().getLast())
                .isEqualTo(new FakePlatformModule.LaunchRequest(BASH, new WorkingDir.At(wsl("/a"))));
    }

    @Test
    @DisplayName("마지막으로 알린 경로가 사라졌으면 재시작은 홈에서 띄우고 알린다")
    void restartFallsBackToHomeWhenLastReportedPathIsMissing() {
        // given
        StartedSession first = start(P1, BASH, Optional.of(new WorkingDir.At(wsl("/a"))));
        first.stream().start();
        streamOf(0).emit(osc7("/b"));
        streamOf(0).exit(0);
        TerminalSession exited = first.exited().toCompletableFuture().join();
        launcher.willFailFor(
                command -> command.workingDirectory().equals(Optional.of("/b")),
                new LaunchError.WorkingDirectoryNotFound("/b"));

        // when
        StartedSession second = starter.restart(P1, exited, SIZE).value().orElseThrow();

        // then
        assertThat(platform.launchRequests())
                .endsWith(
                        new FakePlatformModule.LaunchRequest(BASH, new WorkingDir.At(wsl("/b"))),
                        new FakePlatformModule.LaunchRequest(BASH, new WorkingDir.Home()));
        assertThat(second.notices()).contains(new StartNotice.StartDirMissing(wsl("/b")));
    }

    @Test
    @DisplayName("재시작한 새 세션 등록이 먼저 큐에 들어간 뒤 옛 셸의 종료가 오면 옛 종료는 버려 새 세션은 Running 이다")
    void lateExitOfOldShellAfterRestartIsIgnored() {
        // given: 상태 명령을 손으로 돌리는 액터 — 큐에 들어간 순서를 테스트가 정한다
        ManualExecutor queue = new ManualExecutor();
        StateActor manual = new StateActor(AppState.initial(WorkHistory.empty(HistoryPolicy.DEFAULT)), queue);
        SessionStarter manualStarter = starterOn(manual);
        StartedSession first = manualStarter
                .start(new StartRequest(P1, Optional.of(BASH.id()), false, Optional.empty(), SIZE))
                .value()
                .orElseThrow();
        first.stream().start();
        queue.runAll();
        TerminalSession exited = first.session().exited(0).value().orElseThrow();

        // when: (1) 새 세션 등록 (2) 옛 셸의 종료 순으로 큐에 들어간다
        manualStarter.restart(P1, exited, SIZE).value().orElseThrow();
        streamOf(0).exit(0);
        queue.runAll();

        // then
        SessionLeaf leaf = manual.snapshot().sessions().leaves().getFirst();
        assertThat(manual.snapshot().sessions().leaves()).hasSize(1);
        assertThat(leaf.pane()).isEqualTo(P1);
        assertThat(leaf.status()).isEqualTo(new SessionStatus.Running());
        assertThat(leaf.number()).isEqualTo(1);
    }

    @Test
    @DisplayName("기동에 실패하면 세션을 등록하지 않는다")
    void failedLaunchRegistersNothing() {
        // given
        launcher.willFail(new LaunchError.StartFailed("x"));

        // when
        var result = starter.start(new StartRequest(P1, Optional.of(BASH.id()), false, Optional.empty(), SIZE));

        // then
        assertThat(result.error()).containsInstanceOf(StartFailure.LaunchFailed.class);
        assertThat(state.snapshot().sessions().leaves()).isEmpty();
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("여러 창을 띄우고 재시작해도 셸 통합 설치는 처음 한 번만 하고 결과를 같이 쓴다")
    void installRunsOncePerProcess() {
        // given
        FakeShellIntegration integration = FakeShellIntegration.installedAt(Path.of("integration"));
        platform.withIntegration(integration);
        StartedSession first = startAtHome(P1, BASH);
        startAtHome(P2, BASH);
        first.stream().start();
        streamOf(0).exit(0);
        TerminalSession exited = first.exited().toCompletableFuture().join();

        // when
        starter.restart(P1, exited, SIZE).value().orElseThrow();

        // then
        assertThat(integration.installCalls()).isEqualTo(1);
        assertThat(streamOf(2).subscriberCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("셸 통합 설치가 예외로 끝나면 통합 없음으로 보고 구독 없이 시작 경로를 한 번 기록한다")
    void throwingInstallIsTreatedAsUnavailable() {
        // given
        platform.withIntegration(FakeShellIntegration.throwing(new IllegalStateException("설치 고장")));

        // when
        StartedSession session = start(P1, POWERSHELL, Optional.of(new WorkingDir.At(win("C:\\work"))));

        // then
        assertThat(session.notices()).hasOnlyElementsOfType(StartNotice.IntegrationUnavailable.class);
        assertThat(session.notices()).hasSize(1);
        assertThat(streamOf(0).subscriberCount()).isZero();
        assertThat(count(win("C:\\work"))).isEqualTo(1);
    }
}
