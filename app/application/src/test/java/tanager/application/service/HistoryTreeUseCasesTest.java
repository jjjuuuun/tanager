package tanager.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.in.AppEvent;
import tanager.application.state.AppState;
import tanager.application.state.HistoryStore;
import tanager.application.state.StateActor;
import tanager.domain.history.DirectoryTree;
import tanager.domain.history.EnvironmentNode;
import tanager.domain.history.HistoryError;
import tanager.domain.history.HistoryPolicy;
import tanager.domain.history.HistoryTree;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.TreeQuery;
import tanager.domain.history.VisitedDirectory;
import tanager.domain.history.WorkHistory;
import tanager.domain.history.WorkspacePath;
import tanager.domain.session.IntegrationMode;
import tanager.domain.session.LaunchTemplate;
import tanager.domain.session.LiveSessions;
import tanager.domain.session.ProfileId;
import tanager.domain.session.ShellKind;
import tanager.domain.session.ShellProfile;
import tanager.domain.session.TerminalSession;
import tanager.domain.session.WorkingDir;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.Result;

/** UI 가 트리를 얻고 이력을 바꾸는 입구(#83, FR-HIS-02 · 04 · 06 · 07). 액터는 호출 스레드에서 바로 돈다. */
class HistoryTreeUseCasesTest {
    private static final ShellEnvironment UBUNTU = new ShellEnvironment.Wsl("Ubuntu");
    private static final ShellEnvironment WIN = new ShellEnvironment.Windows();
    private static final Instant T0 = Instant.parse("2026-11-27T00:00:00Z");

    private final List<AppEvent> events = new ArrayList<>();

    private static WorkspacePath wsl(String raw) {
        return WorkspacePath.of(UBUNTU, raw).value().orElseThrow();
    }

    private static WorkspacePath win(String raw) {
        return WorkspacePath.of(WIN, raw).value().orElseThrow();
    }

    private static WorkHistory history(WorkspacePath... paths) {
        WorkHistory history = WorkHistory.empty(HistoryPolicy.DEFAULT);
        for (WorkspacePath path : paths) {
            history = history.recordVisit(path, T0);
        }
        return history;
    }

    private StateActor actor(AppState initial) {
        StateActor actor = new StateActor(initial, Runnable::run);
        actor.subscribe(events::add);
        return actor;
    }

    private static ShellProfile profile(String id, ShellKind kind, ShellEnvironment env) {
        return new ShellProfile(
                new ProfileId(id), id, kind, env, new LaunchTemplate(id, List.of()), IntegrationMode.OSC7);
    }

    private static List<ShellEnvironment> order(HistoryTree tree) {
        return tree.environments().stream().map(EnvironmentNode::env).toList();
    }

    private static <T> T ok(Result<T, HistoryError> result) {
        return result.value().orElseThrow();
    }

    private static <T> Result<T, HistoryError> join(
            java.util.concurrent.CompletionStage<Result<T, HistoryError>> stage) {
        return stage.toCompletableFuture().join();
    }

    @Test
    @DisplayName("트리 조회는 이력 · 세션 · 탐지 순서를 DirectoryTree.project 로 투영한 결과와 같다")
    void treeIsDirectoryTreeProjection() {
        // given
        WorkHistory history = history(wsl("/home/me/a"), win("C:\\work"));
        TerminalSession running = TerminalSession.start(profile("bash", ShellKind.BASH, UBUNTU), new WorkingDir.Home())
                .value()
                .orElseThrow()
                .launched(7)
                .value()
                .orElseThrow();
        LiveSessions sessions = LiveSessions.empty().launched(new PaneId("p1"), running);
        StateActor state = actor(AppState.initial(history).withSessions(sessions));
        new EnvironmentRecorder(state)
                .profilesDetected(List.of(
                        profile("ps", ShellKind.WINDOWS_POWERSHELL, WIN), profile("bash", ShellKind.BASH, UBUNTU)));

        // when
        HistoryTree tree = new HistoryTreeQuery(state).tree("", false);

        // then
        assertThat(tree)
                .isEqualTo(DirectoryTree.project(
                        history, sessions.leaves(), new TreeQuery("", false, List.of(WIN, UBUNTU))));
        assertThat(order(tree)).containsExactly(WIN, UBUNTU);
    }

    @Test
    @DisplayName("탐지 전에는 환경 순서가 이력에 나온 환경의 이름순이다")
    void beforeDetectionEnvironmentsAreByName() {
        // given: 이름순으로 Ubuntu < Windows
        StateActor state = actor(AppState.initial(history(win("C:\\work"), wsl("/home/me"))));

        // when
        HistoryTree tree = new HistoryTreeQuery(state).tree("", false);

        // then
        assertThat(order(tree)).containsExactly(UBUNTU, WIN);
    }

    @Test
    @DisplayName("탐지 순서는 셸 목록에서 환경을 처음 나온 순서로 한 번씩 뽑고 EnvironmentsChanged 를 낸다")
    void detectedOrderIsDistinctInProfileOrder() {
        // given
        StateActor state = actor(AppState.initial(history()));

        // when
        new EnvironmentRecorder(state)
                .profilesDetected(List.of(
                        profile("bash", ShellKind.BASH, UBUNTU),
                        profile("ps", ShellKind.WINDOWS_POWERSHELL, WIN),
                        profile("cmd", ShellKind.CMD, WIN)));

        // then
        assertThat(state.snapshot().environments()).containsExactly(UBUNTU, WIN);
        assertThat(events).containsExactly(new AppEvent.EnvironmentsChanged(List.of(UBUNTU, WIN)));
    }

    @Test
    @DisplayName("고정하면 응답은 바뀐 항목이고 HistoryChanged 가 한 번 나간다")
    void pinRepliesChangedEntryAndEmitsOnce() {
        // given
        StateActor state = actor(AppState.initial(history(wsl("/a"))).withStore(new HistoryStore.Writable()));

        // when
        VisitedDirectory pinned = ok(join(new HistoryManager(state).pin(wsl("/a"), true)));

        // then
        assertThat(pinned.path()).isEqualTo(wsl("/a"));
        assertThat(pinned.pinned()).isTrue();
        assertThat(events).filteredOn(AppEvent.HistoryChanged.class::isInstance).hasSize(1);
        assertThat(state.snapshot().history().find(wsl("/a")).orElseThrow().pinned())
                .isTrue();
    }

    @Test
    @DisplayName("별칭 · 숨김도 바뀐 항목으로 답한다")
    void aliasAndHideReplyChangedEntry() {
        // given
        StateActor state = actor(AppState.initial(history(wsl("/a"))).withStore(new HistoryStore.Writable()));
        HistoryManager manager = new HistoryManager(state);

        // when
        VisitedDirectory aliased = ok(join(manager.alias(wsl("/a"), Optional.of("작업"))));
        VisitedDirectory hidden = ok(join(manager.hide(wsl("/a"), true)));

        // then
        assertThat(aliased.alias())
                .hasValueSatisfying(alias -> assertThat(alias.value()).isEqualTo("작업"));
        assertThat(hidden.hidden()).isTrue();
    }

    @Test
    @DisplayName("잊으면 지운 항목으로 답하고 트리에서 사라진다")
    void forgetRepliesRemovedEntry() {
        // given
        StateActor state =
                actor(AppState.initial(history(wsl("/a"), wsl("/b"))).withStore(new HistoryStore.Writable()));

        // when
        VisitedDirectory forgotten = ok(join(new HistoryManager(state).forget(wsl("/a"))));

        // then
        assertThat(forgotten.path()).isEqualTo(wsl("/a"));
        assertThat(state.snapshot().history().find(wsl("/a"))).isEmpty();
    }

    @Test
    @DisplayName("없는 경로를 잊으려 하면 NotFound 이고 이벤트가 없다")
    void forgetMissingIsNotFoundWithoutEvents() {
        // given
        StateActor state = actor(AppState.initial(history(wsl("/a"))).withStore(new HistoryStore.Writable()));

        // when
        Result<VisitedDirectory, HistoryError> result = join(new HistoryManager(state).forget(wsl("/없음")));

        // then
        assertThat(result.error()).contains(new HistoryError.NotFound(wsl("/없음")));
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("읽기 전용 저장소여도 메모리 이력은 바뀌고 HistoryChanged 가 나간다(저장은 HistorySaver 가 하지 않는다)")
    void readOnlyStoreChangesMemory() {
        // given
        StateActor state = actor(AppState.initial(history(wsl("/a"))).withStore(new HistoryStore.ReadOnly("다른 인스턴스")));

        // when
        ok(join(new HistoryManager(state).pin(wsl("/a"), true)));

        // then
        assertThat(state.snapshot().history().find(wsl("/a")).orElseThrow().pinned())
                .isTrue();
        assertThat(new HistoryTreeQuery(state).tree("", false).pinned()).hasSize(1);
        assertThat(events).filteredOn(AppEvent.HistoryChanged.class::isInstance).hasSize(1);
    }

    @Test
    @DisplayName("65자 별칭은 InvalidAlias 이고 이력은 그대로다")
    void tooLongAliasIsInvalid() {
        // given
        StateActor state = actor(AppState.initial(history(wsl("/a"))).withStore(new HistoryStore.Writable()));
        WorkHistory before = state.snapshot().history();

        // when
        Result<VisitedDirectory, HistoryError> result =
                join(new HistoryManager(state).alias(wsl("/a"), Optional.of("가".repeat(65))));

        // then
        assertThat(result.error())
                .hasValueSatisfying(error -> assertThat(error).isInstanceOf(HistoryError.InvalidAlias.class));
        assertThat(state.snapshot().history()).isEqualTo(before);
        assertThat(events).isEmpty();
    }
}
