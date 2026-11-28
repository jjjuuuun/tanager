package tanager.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.in.AppEvent;
import tanager.application.port.in.OpenError;
import tanager.application.port.in.StartFailure;
import tanager.application.port.in.StartNotice;
import tanager.application.port.in.StartRequest;
import tanager.application.port.in.StartedSession;
import tanager.application.port.out.AppConfig;
import tanager.application.port.out.FakeDirectoryProbe;
import tanager.application.port.out.FakePlatformModule;
import tanager.application.port.out.FakeScheduler;
import tanager.application.port.out.FakeShellIntegration;
import tanager.application.port.out.InMemoryConfigRepository;
import tanager.application.port.out.LoadedConfig;
import tanager.application.port.out.OpenPlacement;
import tanager.application.port.out.ScriptedConversationSource;
import tanager.application.port.out.ScriptedPtyLauncher;
import tanager.application.port.out.ScriptedTerminalStream;
import tanager.application.port.out.TerminalSize;
import tanager.application.state.AppState;
import tanager.application.state.FakeClock;
import tanager.application.state.ManualExecutor;
import tanager.application.state.StateActor;
import tanager.domain.history.ConversationId;
import tanager.domain.history.HistoryPolicy;
import tanager.domain.history.PastConversation;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.VisitSource;
import tanager.domain.history.WorkHistory;
import tanager.domain.history.WorkspacePath;
import tanager.domain.layout.LayoutNode;
import tanager.domain.layout.Orientation;
import tanager.domain.layout.Ratio;
import tanager.domain.session.IntegrationMode;
import tanager.domain.session.LaunchTemplate;
import tanager.domain.session.ProfileId;
import tanager.domain.session.ShellKind;
import tanager.domain.session.ShellLine;
import tanager.domain.session.ShellProfile;
import tanager.domain.session.WorkingDir;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.Result;
import tanager.domain.workspace.PaneSpec;

/**
 * 이전 대화를 새 창에서 이어 하기(FR-TK-09, #216). 새 창을 연 뒤 그 창의 첫 셸이 첫 프롬프트에서 요청한 폴더를 알리면 시작 명령을 한 번
 * 쓴다. 액터는 호출 스레드에서 바로 돌고, 시간은 가짜 스케줄러, 쓰기는 손으로 돌리는 I/O 실행기다.
 */
class ConversationResumerTest {
    private static final ShellEnvironment UBUNTU = new ShellEnvironment.Wsl("Ubuntu");
    private static final ShellEnvironment WIN = new ShellEnvironment.Windows();
    private static final ShellProfile BASH = profile("wsl:Ubuntu", ShellKind.BASH, UBUNTU, IntegrationMode.OSC7);
    private static final ShellProfile CMD = profile("cmd", ShellKind.CMD, WIN, IntegrationMode.NONE);
    private static final TerminalSize SIZE = new TerminalSize(100, 30);
    private static final PaneId P1 = new PaneId("p1");
    private static final PaneId P2 = new PaneId("p2");
    private static final ConversationId ID = new ConversationId("7f3a9c2e-0000-4000-8000-000000000001");
    private static final WorkspacePath FOLDER = wsl("/home/me/a");
    private static final PastConversation CONVERSATION =
            new PastConversation(VisitSource.CLAUDE_CODE, ID, FOLDER, Optional.of("요약"), java.time.Instant.EPOCH);
    private static final String RESUME = "resume " + ID.value() + "\r";

    private final FakePlatformModule platform = new FakePlatformModule(List.of(BASH, CMD));
    private final ScriptedPtyLauncher launcher = new ScriptedPtyLauncher();
    private final InMemoryConfigRepository config = new InMemoryConfigRepository();
    private final FakeScheduler scheduler = new FakeScheduler();
    private final ManualExecutor io = new ManualExecutor();
    private final StateActor state =
            new StateActor(AppState.initial(WorkHistory.empty(HistoryPolicy.DEFAULT)), Runnable::run);
    private final List<AppEvent> events = new ArrayList<>();
    private final ProfileCatalog catalog = new ProfileCatalog(platform);
    private final StartupCommands commands = new StartupCommands(scheduler, io, state);
    private final PaneManager panes = new PaneManager(catalog, config, platform.paths(), state);
    private final ConversationResumer resumer =
            new ConversationResumer(panes, List.of(new ScriptedConversationSource(VisitSource.CLAUDE_CODE)), commands);
    private final SessionStarter starter = new SessionStarter(
            catalog,
            config,
            platform,
            new IntegrationStatus(platform),
            launcher,
            new DefaultProfileMemory(config),
            state,
            new VisitRecorder(state, FakeClock.atEpoch()),
            commands);

    ConversationResumerTest() {
        state.subscribe(events::add);
        config.willLoad(
                Result.ok(new LoadedConfig(new AppConfig(Optional.empty(), Map.of(), OpenPlacement.RIGHT), List.of())));
    }

    private static ShellProfile profile(String id, ShellKind kind, ShellEnvironment env, IntegrationMode mode) {
        return new ShellProfile(new ProfileId(id), id, kind, env, new LaunchTemplate(id + ".exe", List.of()), mode);
    }

    private static WorkspacePath wsl(String raw) {
        return WorkspacePath.of(UBUNTU, raw).value().orElseThrow();
    }

    private static WorkspacePath win(String raw) {
        return WorkspacePath.of(WIN, raw).value().orElseThrow();
    }

    private static String osc7(String path) {
        return "\u001b]7;file://PC" + path + "\u0007";
    }

    private static <T, E> T ok(CompletionStage<Result<T, E>> stage) {
        Result<T, E> result = stage.toCompletableFuture().join();
        return result.value().orElseThrow(() -> new AssertionError("오류: " + result.error()));
    }

    /** UI 가 하듯 새 창의 명세대로 셸을 띄우고 출력 읽기를 시작한다. */
    private ScriptedTerminalStream startFromSpec(PaneSpec spec) {
        StartedSession session = starter.start(new StartRequest(
                        spec.pane(), spec.profile(), false, spec.dir().map(WorkingDir.At::new), SIZE))
                .value()
                .orElseThrow();
        session.stream().start();
        return (ScriptedTerminalStream) session.stream();
    }

    private static List<String> written(ScriptedTerminalStream stream) {
        return stream.written().stream()
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .toList();
    }

    private List<StartNotice> paneNotices(PaneId pane) {
        List<StartNotice> found = new ArrayList<>();
        for (AppEvent event : events) {
            if (event instanceof AppEvent.SessionNotice(PaneId p, StartNotice notice) && p.equals(pane)) {
                found.add(notice);
            }
        }
        return found;
    }

    private static StartNotice skipped(String line, StartNotice.StartupCommandSkipped.Reason reason) {
        return new StartNotice.StartupCommandSkipped(new ShellLine(line), reason);
    }

    @Test
    @DisplayName("대화를 이어 하면 포커스 창 오른쪽에 그 환경의 기본 셸로 대화 폴더에서 여는 창이 생긴다")
    void resumeOpensPaneRightOfFocus() {
        // when
        PaneSpec spec = ok(resumer.resume(CONVERSATION, Optional.empty()));

        // then
        assertThat(spec).isEqualTo(new PaneSpec(P2, Optional.of(BASH.id()), Optional.of(FOLDER)));
        assertThat(state.snapshot().workspace().tab().root())
                .isEqualTo(new LayoutNode.Split(
                        Orientation.ROW, Ratio.HALF, new LayoutNode.Leaf(P1), new LayoutNode.Leaf(P2)));
    }

    @Test
    @DisplayName("첫 보고 전에는 쓰지 않고, 첫 프롬프트가 대화 폴더를 알리면 I/O 실행기에서 이어 하기 명령을 정확히 한 번 쓴다")
    void writesResumeOnceAfterFirstPromptInFolder() {
        // given
        PaneSpec spec = ok(resumer.resume(CONVERSATION, Optional.empty()));
        ScriptedTerminalStream stream = startFromSpec(spec);
        stream.emit("Welcome to Ubuntu\r\n");
        io.runAll();
        assertThat(stream.written()).isEmpty();

        // when
        stream.emit(osc7("/home/me/a") + "$ ");
        io.runAll();

        // then
        assertThat(written(stream)).containsExactly(RESUME);
    }

    @Test
    @DisplayName("두 번째 프롬프트에서는 다시 쓰지 않는다")
    void secondPromptDoesNotWriteAgain() {
        // given
        ScriptedTerminalStream stream = startFromSpec(ok(resumer.resume(CONVERSATION, Optional.empty())));
        stream.emit(osc7("/home/me/a"));
        io.runAll();

        // when
        stream.emit(osc7("/home/me/a"));
        stream.emit(osc7("/home/me/b"));
        stream.emit(osc7("/home/me/a"));
        io.runAll();

        // then
        assertThat(written(stream)).containsExactly(RESUME);
    }

    @Test
    @DisplayName("쓰기는 PTY 읽기 콜백이 아니라 I/O 실행기에서 한다 — 실행기가 돌기 전에는 쓰지 않고 읽기 콜백은 바로 돌아온다")
    void writeRunsOffTheReadThread() {
        // given
        ScriptedTerminalStream stream = startFromSpec(ok(resumer.resume(CONVERSATION, Optional.empty())));

        // when: 읽기 스레드 흉내(emit)가 첫 보고를 낸다
        stream.emit(osc7("/home/me/a"));

        // then
        assertThat(stream.written()).isEmpty();
        assertThat(io.pending()).isEqualTo(1);
        io.runAll();
        assertThat(written(stream)).containsExactly(RESUME);
    }

    @Test
    @DisplayName("경로가 사라져 홈에서 떴으면(첫 보고 /home/me) 쓰지 않고 '경로가 다름' 으로 알린다")
    void missingFolderOpenedAtHomeSkips() {
        // given
        platform.withDirectories(new FakeDirectoryProbe().missing(FOLDER));
        ScriptedTerminalStream stream = startFromSpec(ok(resumer.resume(CONVERSATION, Optional.empty())));

        // when
        stream.emit(osc7("/home/me"));
        io.runAll();
        stream.emit(osc7("/home/me/a"));
        io.runAll();

        // then
        assertThat(stream.written()).isEmpty();
        assertThat(paneNotices(P2))
                .containsExactly(
                        skipped("resume " + ID.value(), StartNotice.StartupCommandSkipped.Reason.PATH_MISMATCH));
    }

    @Test
    @DisplayName("10초 안에 첫 보고가 없으면 쓰지 않고 '시간 초과' 로 알린다 — 늦게 온 보고에도 쓰지 않는다")
    void noReportWithinTimeoutSkips() {
        // given
        ScriptedTerminalStream stream = startFromSpec(ok(resumer.resume(CONVERSATION, Optional.empty())));
        scheduler.advance(Duration.ofMillis(9_999));
        assertThat(paneNotices(P2)).isEmpty();

        // when
        scheduler.advance(Duration.ofMillis(1));
        stream.emit(osc7("/home/me/a"));
        io.runAll();

        // then
        assertThat(stream.written()).isEmpty();
        assertThat(paneNotices(P2))
                .containsExactly(skipped("resume " + ID.value(), StartNotice.StartupCommandSkipped.Reason.TIMED_OUT));
    }

    @Test
    @DisplayName("첫 보고가 오면 시간 초과 예약을 거둔다")
    void reportCancelsTimeout() {
        // given
        ScriptedTerminalStream stream = startFromSpec(ok(resumer.resume(CONVERSATION, Optional.empty())));

        // when
        stream.emit(osc7("/home/me/a"));
        io.runAll();

        // then
        assertThat(scheduler.pendingCount()).isZero();
        scheduler.advance(Duration.ofSeconds(30));
        assertThat(paneNotices(P2)).isEmpty();
    }

    @Test
    @DisplayName("셸 통합이 없는 cmd 는 띄운 뒤 1.5초에 한 번 쓴다")
    void cmdWithoutIntegrationWritesAfterDelay() {
        // given
        WorkspacePath work = win("C:\\work");
        PaneSpec spec = ok(resumer.continueLatest(work, VisitSource.CLAUDE_CODE, Optional.empty()));
        ScriptedTerminalStream stream = startFromSpec(spec);

        // when
        scheduler.advance(Duration.ofMillis(1_499));
        io.runAll();
        assertThat(stream.written()).isEmpty();
        scheduler.advance(Duration.ofMillis(1));
        io.runAll();
        scheduler.advance(Duration.ofSeconds(30));
        io.runAll();

        // then
        assertThat(written(stream)).containsExactly("continue\r");
    }

    @Test
    @DisplayName("셸 통합을 설치하지 못했으면(Unavailable) 통합이 있는 셸도 1.5초 뒤에 쓴다")
    void unavailableIntegrationWritesAfterDelay() {
        // given
        platform.withIntegration(FakeShellIntegration.unavailable());
        ScriptedTerminalStream stream = startFromSpec(ok(resumer.resume(CONVERSATION, Optional.empty())));

        // when
        scheduler.advance(Duration.ofMillis(1_500));
        io.runAll();

        // then
        assertThat(written(stream)).containsExactly(RESUME);
    }

    @Test
    @DisplayName("셸 통합이 없고 경로가 사라져 홈에서 떴으면 쓰지 않고 '경로가 다름' 으로 알린다")
    void withoutIntegrationMissingFolderSkips() {
        // given
        platform.withIntegration(FakeShellIntegration.unavailable());
        platform.withDirectories(new FakeDirectoryProbe().missing(FOLDER));
        ScriptedTerminalStream stream = startFromSpec(ok(resumer.resume(CONVERSATION, Optional.empty())));

        // when
        scheduler.advance(Duration.ofSeconds(5));
        io.runAll();

        // then
        assertThat(stream.written()).isEmpty();
        assertThat(paneNotices(P2))
                .containsExactly(
                        skipped("resume " + ID.value(), StartNotice.StartupCommandSkipped.Reason.PATH_MISMATCH));
    }

    @Test
    @DisplayName("셸이 첫 프롬프트 전에 끝나면 쓰지 않고, 그 창을 재시작해도 쓰지 않는다")
    void exitedBeforePromptAndRestartDoNotWrite() {
        // given
        ScriptedTerminalStream first = startFromSpec(ok(resumer.resume(CONVERSATION, Optional.empty())));
        first.exit(1);
        var exited = state.snapshot().sessions().byPane().get(P2).session();

        // when
        StartedSession again = starter.restart(P2, exited, SIZE).value().orElseThrow();
        again.stream().start();
        ScriptedTerminalStream second = (ScriptedTerminalStream) again.stream();
        second.emit(osc7("/home/me/a"));
        scheduler.advance(Duration.ofSeconds(30));
        io.runAll();

        // then
        assertThat(first.written()).isEmpty();
        assertThat(second.written()).isEmpty();
        assertThat(scheduler.pendingCount()).isZero();
        assertThat(paneNotices(P2))
                .containsExactly(
                        skipped("resume " + ID.value(), StartNotice.StartupCommandSkipped.Reason.SHELL_EXITED));
    }

    @Test
    @DisplayName("창을 닫아 셸이 끝나면 조용히 버린다 — 알림도 쓰기도 없다")
    void closedPaneDropsSilently() {
        // given
        ScriptedTerminalStream stream = startFromSpec(ok(resumer.resume(CONVERSATION, Optional.empty())));

        // when
        ok(panes.close(P2));
        stream.close();
        scheduler.advance(Duration.ofSeconds(30));
        io.runAll();

        // then
        assertThat(stream.written()).isEmpty();
        assertThat(paneNotices(P2)).isEmpty();
        assertThat(scheduler.pendingCount()).isZero();
    }

    @Test
    @DisplayName("셸 선택 화면이 뜨면 미뤄 두었다가, 사용자가 고른 셸이 처음 뜬 뒤 첫 프롬프트에서 쓴다")
    void choiceRequiredDefersUntilChosenShellStarts() {
        // given: 명세의 프로파일이 사라져 셸 선택 화면이 뜬다
        PaneSpec spec = ok(resumer.resume(CONVERSATION, Optional.empty()));
        Result<StartedSession, StartFailure> first = starter.start(new StartRequest(
                spec.pane(), Optional.of(new ProfileId("gone")), false, Optional.of(new WorkingDir.At(FOLDER)), SIZE));
        assertThat(first.error()).containsInstanceOf(StartFailure.ChoiceRequired.class);

        // when
        StartedSession chosen = starter.start(new StartRequest(
                        spec.pane(), Optional.of(BASH.id()), false, Optional.of(new WorkingDir.At(FOLDER)), SIZE))
                .value()
                .orElseThrow();
        chosen.stream().start();
        ScriptedTerminalStream stream = (ScriptedTerminalStream) chosen.stream();
        stream.emit(osc7("/home/me/a"));
        io.runAll();

        // then
        assertThat(written(stream)).containsExactly(RESUME);
    }

    @Test
    @DisplayName("폴더 메뉴의 '여기서 continue' 는 그 폴더에서 새 창을 열고 continueLatest 명령을 쓴다")
    void continueLatestWritesContinue() {
        // given
        PaneSpec spec = ok(resumer.continueLatest(FOLDER, VisitSource.CLAUDE_CODE, Optional.of(OpenPlacement.DOWN)));
        ScriptedTerminalStream stream = startFromSpec(spec);

        // when
        stream.emit(osc7("/home/me/a"));
        io.runAll();

        // then
        assertThat(spec.dir()).contains(FOLDER);
        assertThat(state.snapshot().workspace().tab().root())
                .isEqualTo(new LayoutNode.Split(
                        Orientation.COLUMN, Ratio.HALF, new LayoutNode.Leaf(P1), new LayoutNode.Leaf(P2)));
        assertThat(written(stream)).containsExactly("continue\r");
    }

    @Test
    @DisplayName("등록된 공급자가 없는 출처면 창을 열지 않고 UnknownSource 다")
    void unknownSourceDoesNotOpen() {
        // given
        VisitSource other = new VisitSource("codex");

        // when
        Result<PaneSpec, OpenError> result = resumer.continueLatest(FOLDER, other, Optional.empty())
                .toCompletableFuture()
                .join();

        // then
        assertThat(result.error()).contains(new OpenError.UnknownSource(other));
        assertThat(state.snapshot().workspace().tab().root()).isEqualTo(new LayoutNode.Leaf(P1));
    }

    @Test
    @DisplayName("열 수 없는 환경이면 그 오류를 그대로 돌려주고 시작 명령을 달지 않는다")
    void openErrorIsPassedThrough() {
        // given
        PastConversation elsewhere = new PastConversation(
                VisitSource.CLAUDE_CODE,
                ID,
                WorkspacePath.of(new ShellEnvironment.Wsl("Gone"), "/x").value().orElseThrow(),
                Optional.empty(),
                java.time.Instant.EPOCH);

        // when
        Result<PaneSpec, OpenError> result = resumer.resume(elsewhere, Optional.empty())
                .toCompletableFuture()
                .join();

        // then
        assertThat(result.error()).contains(new OpenError.NoProfileForEnvironment(new ShellEnvironment.Wsl("Gone")));
    }

    @Test
    @DisplayName("시작 명령이 달리지 않은 창은 아무것도 쓰지 않는다(트리에서 연 보통 창)")
    void ordinaryPaneWritesNothing() {
        // given
        PaneSpec spec = ok(panes.open(FOLDER, Optional.empty(), Optional.empty()));
        ScriptedTerminalStream stream = startFromSpec(spec);

        // when
        stream.emit(osc7("/home/me/a"));
        scheduler.advance(Duration.ofSeconds(30));
        io.runAll();

        // then
        assertThat(stream.written()).isEmpty();
        assertThat(paneNotices(P2)).isEmpty();
    }

    @Test
    @DisplayName("시작 명령은 작업 공간 명세에 들어가지 않는다 — 복원 · 저장할 것이 없다")
    void startupCommandIsNotInWorkspace() {
        // when
        PaneSpec spec = ok(resumer.resume(CONVERSATION, Optional.empty()));

        // then
        assertThat(state.snapshot().workspace().spec(P2)).contains(spec);
        assertThat(state.snapshot().workspace().toString()).doesNotContain(ID.value());
    }
}
