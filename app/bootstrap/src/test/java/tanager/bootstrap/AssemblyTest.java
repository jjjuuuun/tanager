package tanager.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tanager.application.port.in.AppEvent;
import tanager.application.port.in.ResolvedAppearance;
import tanager.application.port.in.ResourceReport;
import tanager.application.port.in.StartFailure;
import tanager.application.port.in.StartNotice;
import tanager.application.port.in.StartRequest;
import tanager.application.port.in.StartedSession;
import tanager.application.port.out.DirectUiDispatcher;
import tanager.application.port.out.FakePlatformModule;
import tanager.application.port.out.FakeShellIntegration;
import tanager.application.port.out.ScriptedPtyLauncher;
import tanager.application.port.out.SystemAppearance;
import tanager.application.port.out.TerminalSize;
import tanager.application.port.out.Theme;
import tanager.application.state.HistoryStore;
import tanager.application.state.PendingVisit;
import tanager.application.state.Transition;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.session.IntegrationMode;
import tanager.domain.session.LaunchTemplate;
import tanager.domain.session.ProfileId;
import tanager.domain.session.ProfileResolution;
import tanager.domain.session.ShellKind;
import tanager.domain.session.ShellProfile;
import tanager.domain.shared.PaneId;

class AssemblyTest {
    private static final ShellProfile CMD = new ShellProfile(
            new ProfileId("cmd"),
            "cmd",
            ShellKind.CMD,
            new ShellEnvironment.Windows(),
            new LaunchTemplate("cmd.exe", List.of()),
            IntegrationMode.NONE);

    @TempDir
    Path appData;

    private static final TerminalSize SIZE = new TerminalSize(80, 24);

    // 테스트가 만든 조립은 끝날 때 닫는다 — 저장소 잠금 · 타이머 스레드를 남기지 않는다(TempDir 지우기 전에)
    private final List<Assembly> assemblies = new ArrayList<>();

    @AfterEach
    void shutdownAssemblies() {
        assemblies.forEach(Assembly::shutdown);
    }

    private Assembly assemble(FakePlatformModule platform, ScriptedPtyLauncher launcher) {
        Assembly assembly = Assembly.assemble(platform, launcher, new DirectUiDispatcher());
        assemblies.add(assembly);
        // 앱은 UI 가 구독한 뒤에 부른다(#179). 이 테스트들은 UI 가 없으니 바로 부른다
        assembly.startStore();
        return assembly;
    }

    @Test
    @DisplayName("조립하면 셸 탐지가 미리 한 번 돌고, 기억한 셸이 사라졌으면 셸을 띄우지 않고 선택을 요구한다")
    void assemblePreDetectsShellsAndRequiresChoiceWhenRememberedShellIsGone() throws Exception {
        // given
        Files.writeString(appData.resolve("config.json"), "{ \"defaultProfile\": \"ghost\" }", StandardCharsets.UTF_8);
        FakePlatformModule platform = new FakePlatformModule(List.of(CMD)).withAppData(appData);
        ScriptedPtyLauncher launcher = new ScriptedPtyLauncher();

        // when
        Assembly assembly = assemble(platform, launcher);

        // then 셸 탐지는 start 를 부르기 전에 미리 시작된다
        await().atMost(Duration.ofSeconds(2)).until(() -> platform.shellsCalls() == 1);

        // when
        var result = assembly.sessions().start(StartRequest.remembered(new PaneId("p1"), SIZE));

        // then
        assertThat(result.error())
                .contains(new StartFailure.ChoiceRequired(
                        new ProfileResolution.Choose(List.of(CMD), Optional.of(new ProfileId("ghost"))), List.of()));
        assertThat(launcher.commands()).isEmpty();
        assertThat(platform.shellsCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("기억을 켜고 고르면 config.json 에 기본 셸만 더하고 다음 시작은 선택 없이 그 셸이 뜬다")
    void rememberingWritesDefaultKeepingOtherKeysAndNextStartSkipsChoice() throws Exception {
        // given
        Path file = appData.resolve("config.json");
        Files.writeString(
                file,
                "{\"schemaVersion\":1,\"profiles\":{\"cmd\":{\"startDir\":\"C:\\\\work\"}},\"appearance\":{\"theme\":\"DARK\"}}",
                StandardCharsets.UTF_8);
        FakePlatformModule platform = new FakePlatformModule(List.of(CMD)).withAppData(appData);
        ScriptedPtyLauncher launcher = new ScriptedPtyLauncher();
        Assembly assembly = assemble(platform, launcher);

        // when
        StartedSession first = assembly.sessions()
                .start(StartRequest.chosen(new PaneId("p1"), CMD.id(), true, SIZE))
                .value()
                .orElseThrow();
        first.stream().close();
        StartedSession second = assembly.sessions()
                .start(StartRequest.remembered(new PaneId("p1"), SIZE))
                .value()
                .orElseThrow();

        // then
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(text).containsPattern("\"defaultProfile\"\\s*:\\s*\"cmd\"");
        assertThat(text).containsPattern("\"theme\"\\s*:\\s*\"DARK\"");
        assertThat(text).containsPattern("\"startDir\"\\s*:\\s*\"C:\\\\\\\\work\"");
        assertThat(second.session().profile()).isEqualTo(CMD);
        second.stream().close();
    }

    @Test
    @DisplayName("손상된 config.json 이어도 셸은 뜨고 기본 셸 저장 실패를 알리며 파일은 그대로다")
    void corruptConfigIsNotOverwrittenWhenRemembering() throws Exception {
        // given
        Path file = appData.resolve("config.json");
        Files.writeString(file, "{ broken", StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(file);
        FakePlatformModule platform = new FakePlatformModule(List.of(CMD)).withAppData(appData);
        Assembly assembly = assemble(platform, new ScriptedPtyLauncher());

        // when
        StartedSession started = assembly.sessions()
                .start(StartRequest.chosen(new PaneId("p1"), CMD.id(), true, SIZE))
                .value()
                .orElseThrow();

        // then
        assertThat(started.notices()).anyMatch(StartNotice.DefaultNotSaved.class::isInstance);
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
        started.stream().close();
    }

    @Test
    @DisplayName("셸 탐지가 끝나면 그 순서가 상태의 환경 순서가 된다(#83)")
    void detectedShellsSetEnvironmentOrder() {
        // given
        FakePlatformModule platform = new FakePlatformModule(List.of(CMD)).withAppData(appData);

        // when
        Assembly assembly = assemble(platform, new ScriptedPtyLauncher());

        // then
        await().atMost(Duration.ofSeconds(2))
                .until(() -> assembly.state().snapshot().environments().equals(List.of(CMD.env())));
    }

    @Test
    @DisplayName("조립하면 셸 통합 설치가 백그라운드에서 한 번 돈다")
    void assembleInstallsShellIntegrationOnce() {
        // given
        FakeShellIntegration integration = FakeShellIntegration.installedAt(appData.resolve("shell-integration"));
        FakePlatformModule platform =
                new FakePlatformModule(List.of(CMD)).withAppData(appData).withIntegration(integration);

        // when
        assemble(platform, new ScriptedPtyLauncher());

        // then
        await().atMost(Duration.ofSeconds(2)).until(() -> integration.installCalls() == 1);
        assertThat(integration.installCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("조립 때 데운 셸 통합 설치 결과를 셸 시작이 같이 써서 설치는 프로세스에서 한 번이다")
    void startSharesInstallationWarmedByAssembly() {
        // given
        FakeShellIntegration integration = FakeShellIntegration.installedAt(appData.resolve("shell-integration"));
        FakePlatformModule platform =
                new FakePlatformModule(List.of(CMD)).withAppData(appData).withIntegration(integration);
        Assembly assembly = assemble(platform, new ScriptedPtyLauncher());
        await().atMost(Duration.ofSeconds(2)).until(() -> integration.installCalls() == 1);

        // when
        StartedSession first = assembly.sessions()
                .start(StartRequest.chosen(new PaneId("p1"), CMD.id(), false, SIZE))
                .value()
                .orElseThrow();
        StartedSession second = assembly.sessions()
                .start(StartRequest.chosen(new PaneId("p2"), CMD.id(), false, SIZE))
                .value()
                .orElseThrow();

        // then
        assertThat(integration.installCalls()).isEqualTo(1);
        first.stream().close();
        second.stream().close();
    }

    @Test
    @DisplayName("셸 통합 설치가 예외를 던져도 조립과 셸 시작은 된다")
    void throwingShellIntegrationDoesNotBreakAssemblyOrStart() {
        // given
        FakeShellIntegration integration = FakeShellIntegration.throwing(new IllegalStateException("설치 고장"));
        FakePlatformModule platform =
                new FakePlatformModule(List.of(CMD)).withAppData(appData).withIntegration(integration);
        ScriptedPtyLauncher launcher = new ScriptedPtyLauncher();

        // when
        Assembly assembly = assemble(platform, launcher);
        await().atMost(Duration.ofSeconds(2)).until(() -> integration.installCalls() == 1);
        var result = assembly.sessions().start(StartRequest.chosen(new PaneId("p1"), CMD.id(), false, SIZE));

        // then
        StartedSession started = result.value().orElseThrow();
        assertThat(started.session().profile()).isEqualTo(CMD);
        assertThat(launcher.commands()).hasSize(1);
        started.stream().close();
    }

    private static final WorkspacePath INFRA = WorkspacePath.of(new ShellEnvironment.Windows(), "C:\\work\\infra")
            .value()
            .orElseThrow();
    private static final WorkspacePath NEW_DIR = WorkspacePath.of(new ShellEnvironment.Windows(), "C:\\work\\new")
            .value()
            .orElseThrow();

    private static String stateJson(int schemaVersion) {
        return """
                {"schemaVersion":%d,"history":{"entries":[
                  {"env":{"kind":"windows"},"path":"C:\\\\work\\\\infra",
                   "firstVisited":"2026-11-21T01:00:00Z","lastVisited":"2026-11-23T08:30:00Z",
                   "visitCount":5,"pinned":false,"alias":null,"hidden":false,"sources":["shell"]}]}}
                """.formatted(schemaVersion);
    }

    private Assembly assembleWithState(String json) throws Exception {
        if (json != null) {
            Files.writeString(appData.resolve("state.json"), json, StandardCharsets.UTF_8);
        }
        FakePlatformModule platform = new FakePlatformModule(List.of(CMD)).withAppData(appData);
        return assemble(platform, new ScriptedPtyLauncher());
    }

    private static void visit(Assembly assembly, WorkspacePath path) {
        // VisitRecorder 와 같은 규칙: 불러오는 중이면 방문을 쌓아 두어 불러온 이력 위에 다시 적용되게 한다.
        // VisitRecorder 의 대기열 규칙과 같게 유지한다(바뀌면 여기도 바꾼다)
        assembly.state().submit(current -> {
            Instant at = Instant.parse("2026-11-27T00:00:00Z");
            var next = current.history().recordVisit(path, at);
            HistoryStore store = current.store() instanceof HistoryStore.Loading loading
                    ? loading.queued(new PendingVisit(path, at))
                    : current.store();
            return new Transition<>(
                    current.withHistory(next).withStore(store), List.of(new AppEvent.HistoryChanged(next)), true);
        });
    }

    @Test
    @DisplayName("조립하면 appData 의 state.json 이력을 백그라운드에서 불러와 쓸 수 있는 상태가 된다")
    void assembleLoadsHistoryFromStateJson() throws Exception {
        // given / when
        Assembly assembly = assembleWithState(stateJson(1));

        // then
        await().atMost(Duration.ofSeconds(5))
                .until(() -> assembly.state().snapshot().store() instanceof HistoryStore.Writable);
        assertThat(assembly.state()
                        .snapshot()
                        .history()
                        .find(INFRA)
                        .orElseThrow()
                        .visitCount())
                .isEqualTo(5);
    }

    @Test
    @DisplayName("불러오기 전에 기록된 방문은 불러온 이력 위에 합쳐진다")
    void visitBeforeLoadIsMergedIntoLoadedHistory() throws Exception {
        // given / when 조립 직후 곧바로 방문한다(불러오기와 경합해도 어느 쪽이든 합쳐져야 한다)
        Assembly assembly = assembleWithState(stateJson(1));
        visit(assembly, INFRA);

        // then
        await().atMost(Duration.ofSeconds(5))
                .until(() -> assembly.state().snapshot().store() instanceof HistoryStore.Writable
                        && assembly.state()
                                        .snapshot()
                                        .history()
                                        .find(INFRA)
                                        .map(e -> e.visitCount())
                                        .orElse(0)
                                == 6);
    }

    @Test
    @DisplayName("불러오기 전에 기록된 방문은 종료 때 불러온 이력과 합쳐 state.json 에 저장된다")
    void visitBeforeLoadIsSavedOnShutdown() throws Exception {
        // given 조립 직후 곧바로 방문한다. 불러오기와 경합해 어느 쪽이 먼저든 count 6 이 저장돼야 한다
        //       (불러오는 동안의 방문 경로를 결정적으로 보는 것은 HistorySaverTest)
        Assembly assembly = assembleWithState(stateJson(1));
        visit(assembly, INFRA);
        await().atMost(Duration.ofSeconds(5))
                .until(() -> assembly.state().snapshot().store() instanceof HistoryStore.Writable
                        && assembly.state()
                                        .snapshot()
                                        .history()
                                        .find(INFRA)
                                        .map(e -> e.visitCount())
                                        .orElse(0)
                                == 6);

        // when
        assembly.shutdown();

        // then
        assertThat(Files.readString(appData.resolve("state.json"), StandardCharsets.UTF_8))
                .containsPattern("\"visitCount\"\\s*:\\s*6");
    }

    @Test
    @DisplayName("종료(shutdown)는 미저장 변경을 state.json 에 저장하고 이후 명령은 거부된다")
    void shutdownFlushesUnsavedChangesAndRejectsLaterCommands() throws Exception {
        // given
        Assembly assembly = assembleWithState(null);
        await().atMost(Duration.ofSeconds(5))
                .until(() -> assembly.state().snapshot().store() instanceof HistoryStore.Writable);
        visit(assembly, NEW_DIR);
        await().atMost(Duration.ofSeconds(5))
                .until(() -> assembly.state().snapshot().history().find(NEW_DIR).isPresent());

        // when
        assembly.shutdown();

        // then
        assertThat(Files.readString(appData.resolve("state.json"), StandardCharsets.UTF_8))
                .contains("C:\\\\work\\\\new");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        assembly.state()
                .submit(current -> new Transition<>(current, List.of(), true))
                .whenComplete((r, e) -> failure.set(e));
        assertThat(failure.get()).isNotNull();
    }

    @Test
    @DisplayName("방문이 없으면 shutdown 이 state.json 을 만들지도 바꾸지도 않는다")
    void shutdownWithoutChangesLeavesStateFileUntouched() throws Exception {
        // given
        Assembly assembly = assembleWithState(stateJson(1));
        await().atMost(Duration.ofSeconds(5))
                .until(() -> assembly.state().snapshot().store() instanceof HistoryStore.Writable);
        byte[] before = Files.readAllBytes(appData.resolve("state.json"));

        // when
        assembly.shutdown();

        // then
        assertThat(Files.readAllBytes(appData.resolve("state.json"))).isEqualTo(before);
    }

    @Test
    @DisplayName("새 버전이 쓴 state.json 은 읽기 전용으로 열고 방문해도 종료 때 파일을 바꾸지 않는다")
    void newerSchemaIsReadOnlyAndNeverOverwritten() throws Exception {
        // given
        Assembly assembly = assembleWithState(stateJson(99));
        byte[] before = Files.readAllBytes(appData.resolve("state.json"));
        await().atMost(Duration.ofSeconds(5))
                .until(() -> assembly.state().snapshot().store() instanceof HistoryStore.ReadOnly);

        // when
        visit(assembly, NEW_DIR);
        await().atMost(Duration.ofSeconds(5))
                .until(() -> assembly.state().snapshot().history().find(NEW_DIR).isPresent());
        assembly.shutdown();

        // then
        assertThat(Files.readAllBytes(appData.resolve("state.json"))).isEqualTo(before);
    }

    // ---- 설정 즉시 반영(#182) ----

    private static List<AppEvent> settingsChanges(List<AppEvent> events) {
        return events.stream()
                .filter(e -> e instanceof AppEvent.SettingsChanged)
                .toList();
    }

    @Test
    @DisplayName("설정을 시작하면 SettingsChanged 가 한 번 나가고, 실행 중 terminal.size 를 16 으로 저장하면 1초 안에 한 번 더 나간다")
    void settingsAnnounceAtStartAndReloadOnSave() throws Exception {
        // given
        Path file = appData.resolve("config.json");
        Files.writeString(file, "// 내 설정\n{ \"terminal\": { \"size\": 14 } }", StandardCharsets.UTF_8);
        Assembly assembly =
                assemble(new FakePlatformModule(List.of(CMD)).withAppData(appData), new ScriptedPtyLauncher());
        List<AppEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        assembly.state().subscribe(events::add);

        // when
        assembly.startSettings();

        // then
        await().atMost(Duration.ofSeconds(1))
                .until(() -> settingsChanges(events).size() == 1);
        assertThat(assembly.settings().current().terminal().size()).isEqualTo(14);

        // when
        Files.writeString(file, "// 내 설정\n{ \"terminal\": { \"size\": 16 } }", StandardCharsets.UTF_8);

        // then
        await().atMost(Duration.ofSeconds(1))
                .until(() -> settingsChanges(events).size() == 2);
        assertThat(((AppEvent.SettingsChanged) settingsChanges(events).get(1))
                        .config()
                        .terminal()
                        .size())
                .isEqualTo(16);
        await().during(Duration.ofMillis(800))
                .atMost(Duration.ofSeconds(3))
                .until(() -> settingsChanges(events).size() == 2);
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).startsWith("// 내 설정");
    }

    @Test
    @DisplayName("config.json 이 없을 때 설정 파일을 열면 schemaVersion 1 만 담은 파일을 만든다")
    void settingsFileIsCreatedWhenMissing() throws Exception {
        // given
        Assembly assembly =
                assemble(new FakePlatformModule(List.of(CMD)).withAppData(appData), new ScriptedPtyLauncher());

        // when
        Path file = assembly.settings().file().value().orElseThrow();

        // then
        assertThat(file).isEqualTo(appData.resolve("config.json"));
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).containsPattern("\"schemaVersion\"\\s*:\\s*1");
    }

    @Test
    @DisplayName("리소스 표시를 구독하면 플랫폼의 수집기로 재서 보내고, 종료하면 멈춘다(#187)")
    void resourcesUsePlatformProbe() {
        // given
        FakePlatformModule platform = new FakePlatformModule(List.of(CMD)).withAppData(appData);
        Assembly assembly = assemble(platform, new ScriptedPtyLauncher());
        List<ResourceReport> reports = new CopyOnWriteArrayList<>();

        // when
        assembly.resources().subscribe(reports::add);

        // then
        await().atMost(Duration.ofSeconds(2)).until(() -> !reports.isEmpty());
        assertThat(platform.resources().hostCalls()).isPositive();
        assertThat(platform.resources().wslCalls()).isEmpty();
    }

    @Test
    @DisplayName("시작 때 OS 외관을 한 번 읽어 current() 가 되고, 설정 theme 이 DARK 면 OS 가 라이트여도 다크가 된다(#184)")
    void appearanceFollowsOsThenSettings() throws Exception {
        // given
        FakePlatformModule platform = new FakePlatformModule(List.of(CMD))
                .withAppData(appData)
                .withAppearance(new SystemAppearance(Optional.of(Theme.LIGHT), false));
        Files.writeString(
                appData.resolve("config.json"),
                "{\"schemaVersion\":1,\"appearance\":{\"theme\":\"DARK\"}}",
                StandardCharsets.UTF_8);
        Assembly assembly = assemble(platform, new ScriptedPtyLauncher());

        // when
        ResolvedAppearance initial = assembly.readAppearance();
        assembly.startSettings();

        // then
        assertThat(initial).isEqualTo(new ResolvedAppearance(Theme.LIGHT, false));
        await().atMost(Duration.ofSeconds(3))
                .until(() -> assembly.appearance().current().equals(new ResolvedAppearance(Theme.DARK, false)));
        assertThat(platform.appearanceCalls()).isEqualTo(1);
    }
}
