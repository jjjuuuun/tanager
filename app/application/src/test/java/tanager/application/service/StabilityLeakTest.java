package tanager.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.in.StartRequest;
import tanager.application.port.in.StartedSession;
import tanager.application.port.out.AppConfig;
import tanager.application.port.out.FakePlatformModule;
import tanager.application.port.out.FakeScheduler;
import tanager.application.port.out.InMemoryConfigRepository;
import tanager.application.port.out.LoadedConfig;
import tanager.application.port.out.OpenPlacement;
import tanager.application.port.out.ScriptedConversationSource;
import tanager.application.port.out.ScriptedPtyLauncher;
import tanager.application.port.out.ScriptedTerminalStream;
import tanager.application.port.out.TerminalSize;
import tanager.application.state.AppState;
import tanager.application.state.FakeClock;
import tanager.application.state.StateActor;
import tanager.domain.history.ConversationId;
import tanager.domain.history.HistoryPolicy;
import tanager.domain.history.PastConversation;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.VisitSource;
import tanager.domain.history.WorkHistory;
import tanager.domain.history.WorkspacePath;
import tanager.domain.layout.Orientation;
import tanager.domain.session.IntegrationMode;
import tanager.domain.session.LaunchTemplate;
import tanager.domain.session.ProfileId;
import tanager.domain.session.ShellKind;
import tanager.domain.session.ShellProfile;
import tanager.domain.session.TerminalSession;
import tanager.domain.session.WorkingDir;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.Result;
import tanager.domain.shared.TabId;
import tanager.domain.workspace.PaneSpec;
import tanager.domain.workspace.Tab;
import tanager.domain.workspace.Workspace;

/**
 * 창 · 탭 · 셸을 되풀이해 열고 닫아도 상태 · 구독 · 예약이 쌓이지 않는다(#223, NFR-01 · NFR-03). UI 가 하는 일(새 창의 셸 띄우기 · 닫은
 * 창의 스트림 닫기)은 테스트가 흉내 낸다. 액터는 호출 스레드에서 바로 돈다.
 */
class StabilityLeakTest {
    private static final ShellEnvironment UBUNTU = new ShellEnvironment.Wsl("Ubuntu");
    private static final ShellProfile BASH = new ShellProfile(
            new ProfileId("wsl:Ubuntu"),
            "Ubuntu",
            ShellKind.BASH,
            UBUNTU,
            new LaunchTemplate("wsl.exe", List.of()),
            IntegrationMode.OSC7);
    private static final TerminalSize SIZE = new TerminalSize(100, 30);
    private static final PaneId P1 = new PaneId("p1");
    private static final WorkspacePath FOLDER =
            WorkspacePath.of(UBUNTU, "/home/me/a").value().orElseThrow();
    private static final PastConversation CONVERSATION = new PastConversation(
            VisitSource.CLAUDE_CODE,
            new ConversationId("7f3a9c2e-0000-4000-8000-000000000001"),
            FOLDER,
            Optional.empty(),
            Instant.EPOCH);
    private static final int SPLITS = 50;
    private static final int TABS = 20;
    private static final int RESTARTS = 20;

    private final FakePlatformModule platform = new FakePlatformModule(List.of(BASH));
    private final ScriptedPtyLauncher launcher = new ScriptedPtyLauncher();
    private final InMemoryConfigRepository config = new InMemoryConfigRepository();
    private final FakeScheduler scheduler = new FakeScheduler();
    private final StateActor state =
            new StateActor(AppState.initial(WorkHistory.empty(HistoryPolicy.DEFAULT)), Runnable::run);
    private final ProfileCatalog catalog = new ProfileCatalog(platform);
    private final StartupCommands commands = new StartupCommands(scheduler, Runnable::run, state);
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

    StabilityLeakTest() {
        config.willLoad(Result.ok(
                new LoadedConfig(new AppConfig(Optional.of(BASH.id()), Map.of(), OpenPlacement.RIGHT), List.of())));
    }

    private static <T, E> T ok(CompletionStage<Result<T, E>> stage) {
        Result<T, E> result = stage.toCompletableFuture().join();
        return result.value().orElseThrow(() -> new AssertionError("오류: " + result.error()));
    }

    private Workspace workspace() {
        return state.snapshot().workspace();
    }

    private static Set<PaneId> panesOf(Workspace workspace) {
        Set<PaneId> all = new HashSet<>();
        for (Tab tab : workspace.tabs()) {
            all.addAll(tab.root().panes());
        }
        return all;
    }

    /** UI 가 하듯 창의 명세대로 셸을 띄우고 읽기를 시작한 뒤 셸이 첫 프롬프트에서 경로를 알린다. */
    private ScriptedTerminalStream startAndReport(PaneId pane) {
        PaneSpec spec = workspace().spec(pane).orElseThrow();
        StartedSession session = starter.start(
                        new StartRequest(pane, spec.profile(), false, spec.dir().map(WorkingDir.At::new), SIZE))
                .value()
                .orElseThrow();
        session.stream().start();
        ScriptedTerminalStream stream = (ScriptedTerminalStream) session.stream();
        stream.emit("\u001b]7;file://PC/home/me\u0007$ ");
        return stream;
    }

    private PaneId added(Workspace before, Workspace after) {
        Set<PaneId> fresh = panesOf(after);
        fresh.removeAll(panesOf(before));
        assertThat(fresh).hasSize(1);
        return fresh.iterator().next();
    }

    @Test
    @DisplayName("분할 → 셸 → 닫기를 50번 해도 세션 · 상태 구독은 기준선이고 닫은 창의 스트림에는 구독이 남지 않는다")
    void splitAndCloseRepeatedly() {
        // given
        ScriptedTerminalStream first = startAndReport(P1);
        int listeners = state.listenerCount();
        int firstSubscribers = first.subscriberCount();

        // when
        for (int round = 0; round < SPLITS; round++) {
            Workspace before = workspace();
            PaneId pane = added(before, ok(panes.split(Orientation.ROW)));
            ScriptedTerminalStream stream = startAndReport(pane);
            ok(panes.close(pane));
            stream.close();
        }

        // then
        assertThat(panesOf(workspace())).containsExactly(P1);
        assertThat(workspace().specs()).containsOnlyKeys(P1);
        assertThat(state.snapshot().sessions().byPane()).containsOnlyKeys(P1);
        assertThat(state.listenerCount()).isEqualTo(listeners);
        List<ScriptedTerminalStream> closed =
                launcher.streams().subList(1, launcher.streams().size());
        assertThat(closed).hasSize(SPLITS).allSatisfy(stream -> {
            assertThat(stream.isClosed()).isTrue();
            assertThat(stream.subscriberCount()).isZero();
        });
        assertThat(first.subscriberCount()).isEqualTo(firstSubscribers);
        assertThat(scheduler.pendingCount()).isZero();
    }

    @Test
    @DisplayName("탭 만들기 → 셸 → 닫기를 20번 해도 세션 · 상태 구독은 기준선이고 닫은 탭의 스트림에는 구독이 남지 않는다")
    void createAndCloseTabsRepeatedly() {
        // given
        startAndReport(P1);
        int listeners = state.listenerCount();

        // when
        for (int round = 0; round < TABS; round++) {
            Workspace before = workspace();
            Workspace created = ok(panes.create());
            PaneId pane = added(before, created);
            TabId tab = created.tab().id();
            ScriptedTerminalStream stream = startAndReport(pane);
            ok(panes.close(tab));
            stream.close();
        }

        // then
        assertThat(workspace().tabs()).hasSize(1);
        assertThat(workspace().specs()).containsOnlyKeys(P1);
        assertThat(state.snapshot().sessions().byPane()).containsOnlyKeys(P1);
        assertThat(state.listenerCount()).isEqualTo(listeners);
        assertThat(launcher.streams().subList(1, launcher.streams().size()))
                .hasSize(TABS)
                .allSatisfy(stream -> assertThat(stream.subscriberCount()).isZero());
    }

    @Test
    @DisplayName("셸 종료 → 재시작을 20번 해도 끝난 셸의 구독 · 시작 명령 예약이 남지 않고 창의 세션은 하나다")
    void restartExitedShellRepeatedly() {
        // given — 이어 하기 창은 첫 보고를 기다리는 예약을 단다. 보고 전에 셸이 끝나면 그 예약을 거둬야 한다
        int listeners = state.listenerCount();
        PaneSpec spec = ok(resumer.resume(CONVERSATION, Optional.empty()));
        StartedSession session = starter.start(new StartRequest(
                        spec.pane(), spec.profile(), false, spec.dir().map(WorkingDir.At::new), SIZE))
                .value()
                .orElseThrow();
        session.stream().start();
        assertThat(scheduler.pendingCount()).isEqualTo(1);

        // when
        for (int round = 0; round < RESTARTS; round++) {
            ((ScriptedTerminalStream) session.stream()).exit(0);
            TerminalSession exited = session.exited().toCompletableFuture().join();
            session = starter.restart(spec.pane(), exited, SIZE).value().orElseThrow();
            session.stream().start();
        }

        // then
        List<ScriptedTerminalStream> streams = launcher.streams();
        assertThat(streams).hasSize(RESTARTS + 1);
        assertThat(streams.subList(0, RESTARTS))
                .allSatisfy(stream -> assertThat(stream.subscriberCount()).isZero());
        assertThat(state.snapshot().sessions().byPane()).containsOnlyKeys(spec.pane());
        assertThat(scheduler.pendingCount()).isZero();
        assertThat(commands.pendingCount()).isZero();
        assertThat(state.listenerCount()).isEqualTo(listeners);
    }
}
