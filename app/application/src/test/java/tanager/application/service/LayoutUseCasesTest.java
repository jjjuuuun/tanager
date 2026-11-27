package tanager.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.in.AppEvent;
import tanager.application.port.out.InMemoryConfigRepository;
import tanager.application.state.AppState;
import tanager.application.state.StateActor;
import tanager.application.state.Transition;
import tanager.domain.history.HistoryPolicy;
import tanager.domain.history.PathTranslator;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkHistory;
import tanager.domain.history.WorkspacePath;
import tanager.domain.history.WslMount;
import tanager.domain.layout.Direction;
import tanager.domain.layout.DropZone;
import tanager.domain.layout.Edge;
import tanager.domain.layout.LayoutError;
import tanager.domain.layout.LayoutNode;
import tanager.domain.layout.Orientation;
import tanager.domain.layout.Ratio;
import tanager.domain.layout.Rect;
import tanager.domain.layout.SplitPath;
import tanager.domain.session.IntegrationMode;
import tanager.domain.session.LaunchTemplate;
import tanager.domain.session.ProfileId;
import tanager.domain.session.ShellKind;
import tanager.domain.session.ShellProfile;
import tanager.domain.session.TerminalSession;
import tanager.domain.session.WorkingDir;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.Result;
import tanager.domain.workspace.PaneSpec;
import tanager.domain.workspace.Workspace;

/** 분할 · 크기 · 방향 포커스 · 줌 · 교환 · 옮기기 유스케이스(#147, FR-LAY-01~06 · 09 · 12). */
class LayoutUseCasesTest {
    private static final ShellEnvironment WIN = new ShellEnvironment.Windows();
    private static final ShellProfile CMD = new ShellProfile(
            new ProfileId("cmd"),
            "cmd",
            ShellKind.CMD,
            WIN,
            new LaunchTemplate("cmd.exe", List.of()),
            IntegrationMode.NONE);
    private static final ProfileId PWSH = new ProfileId("powershell");
    private static final PaneId P1 = new PaneId("p1");
    private static final PaneId P2 = new PaneId("p2");
    private static final PaneId P3 = new PaneId("p3");
    private static final WorkspacePath A =
            WorkspacePath.of(WIN, "C:\\a").value().orElseThrow();
    private static final WorkspacePath B =
            WorkspacePath.of(WIN, "C:\\b").value().orElseThrow();
    private static final WorkspacePath START =
            WorkspacePath.of(WIN, "C:\\start").value().orElseThrow();
    private static final Rect AREA = new Rect(0, 0, 200, 100);
    private static final int GAP = 6;

    private final StateActor state =
            new StateActor(AppState.initial(WorkHistory.empty(HistoryPolicy.DEFAULT)), Runnable::run);
    private final List<AppEvent> events = new ArrayList<>();
    private final PaneManager panes = new PaneManager(
            () -> List.of(CMD),
            new InMemoryConfigRepository(),
            (from, to) -> PathTranslator.translate(from, to, WslMount.DEFAULT),
            state);

    LayoutUseCasesTest() {
        state.subscribe(events::add);
    }

    private void given(UnaryOperator<AppState> change) {
        state.submit(current -> new Transition<>(change.apply(current), List.of(), true))
                .toCompletableFuture()
                .join();
        events.clear();
    }

    /** p1 | p2, 포커스 p1. p2 의 명세는 (powershell, C:\b). */
    private void givenTwoPanes() {
        given(current -> current.withWorkspace(Workspace.initial()
                .openBeside(Orientation.ROW, PWSH, Optional.of(B))
                .workspace()
                .focus(P1)
                .value()
                .orElseThrow()));
    }

    /** 창 p1 에 cmd 가 {@code start} 에서 떴다. */
    private void givenRunning(WorkspacePath start) {
        TerminalSession running = TerminalSession.start(CMD, new WorkingDir.At(start))
                .value()
                .orElseThrow()
                .launched(42)
                .value()
                .orElseThrow();
        given(current -> current.withSessions(current.sessions().launched(P1, running)));
    }

    private void givenReported(WorkspacePath cwd) {
        given(current ->
                current.withSessions(current.sessions().cwdReported(P1, cwd).orElseThrow()));
    }

    private static <T, E> T ok(CompletionStage<Result<T, E>> stage) {
        Result<T, E> result = stage.toCompletableFuture().join();
        return result.value().orElseThrow(() -> new AssertionError("오류: " + result.error()));
    }

    private static <T, E> E err(CompletionStage<Result<T, E>> stage) {
        return stage.toCompletableFuture().join().error().orElseThrow();
    }

    private Workspace workspace() {
        return state.snapshot().workspace();
    }

    @Test
    @DisplayName("p1(cmd, 셸이 알린 cwd C:\\a)을 split(ROW) 하면 p2 명세는 (cmd, C:\\a), 포커스 p2, LayoutChanged 한 번")
    void splitUsesFocusedSessionProfileAndCwd() {
        // given
        givenRunning(START);
        givenReported(A);

        // when
        Workspace split = ok(panes.split(Orientation.ROW));

        // then
        assertThat(split.spec(P2)).contains(new PaneSpec(P2, Optional.of(CMD.id()), Optional.of(A)));
        assertThat(split.tab().root())
                .isEqualTo(new LayoutNode.Split(
                        Orientation.ROW, Ratio.HALF, new LayoutNode.Leaf(P1), new LayoutNode.Leaf(P2)));
        assertThat(split.tab().focused()).isEqualTo(P2);
        assertThat(workspace()).isEqualTo(split);
        assertThat(events).containsExactly(new AppEvent.LayoutChanged(split));
    }

    @Test
    @DisplayName("셸이 아직 경로를 알리지 않았으면 그 세션의 시작 경로에서 연다")
    void splitBeforeReportUsesStartDir() {
        // given
        givenRunning(START);

        // when
        Workspace split = ok(panes.split(Orientation.COLUMN));

        // then
        assertThat(split.spec(P2)).contains(new PaneSpec(P2, Optional.of(CMD.id()), Optional.of(START)));
    }

    @Test
    @DisplayName("포커스 창에 세션이 없으면 그 창의 명세(프로파일 · 경로)를 그대로 쓴다")
    void splitWithoutSessionUsesSpec() {
        // given: p1 | p2, 포커스를 p2(powershell, C:\b)로
        givenTwoPanes();
        given(current ->
                current.withWorkspace(current.workspace().focus(P2).value().orElseThrow()));

        // when
        Workspace split = ok(panes.split(Orientation.ROW));

        // then
        assertThat(split.spec(P3)).contains(new PaneSpec(P3, Optional.of(PWSH), Optional.of(B)));
    }

    @Test
    @DisplayName("포커스 창의 프로파일을 모르면(첫 창 · 세션 없음) 새 창도 기억한 셸 · 시작 경로로 연다")
    void splitUnknownProfileUsesRemembered() {
        // when
        Workspace split = ok(panes.split(Orientation.ROW));

        // then
        assertThat(split.spec(P2)).contains(PaneSpec.remembered(P2));
    }

    @Test
    @DisplayName("p1 | p2 에서 focus(RIGHT) 는 p2, 오른쪽에 창이 없으면 NoNeighbor 이고 상태 · 이벤트는 그대로다")
    void focusByDirection() {
        // given
        givenTwoPanes();

        // when
        Workspace right = ok(panes.focus(Direction.RIGHT, AREA, GAP));

        // then
        assertThat(right.tab().focused()).isEqualTo(P2);
        assertThat(events).containsExactly(new AppEvent.LayoutChanged(right));

        // when: p2 에서 더 오른쪽
        events.clear();
        LayoutError error = err(panes.focus(Direction.RIGHT, AREA, GAP));

        // then
        assertThat(error).isEqualTo(new LayoutError.NoNeighbor(P2, Direction.RIGHT));
        assertThat(workspace()).isEqualTo(right);
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("줌을 켜고 끌 때마다 LayoutChanged 한 번, 창이 하나면 성공하지만 이벤트는 없다")
    void toggleZoom() {
        // when: 창이 하나
        Workspace single = ok(panes.toggle());

        // then
        assertThat(single).isEqualTo(Workspace.initial());
        assertThat(events).isEmpty();

        // given
        givenTwoPanes();

        // when
        Workspace zoomed = ok(panes.toggle());
        Workspace unzoomed = ok(panes.toggle());

        // then
        assertThat(zoomed.tab().zoomed()).contains(P1);
        assertThat(unzoomed.tab().zoomed()).isEmpty();
        assertThat(events).containsExactly(new AppEvent.LayoutChanged(zoomed), new AppEvent.LayoutChanged(unzoomed));
    }

    @Test
    @DisplayName("줌 상태에서 다른 창 포커스 · 분할 · 닫기를 하면 줌이 풀린다")
    void zoomClearedByFocusSplitClose() {
        // given
        givenTwoPanes();
        ok(panes.toggle());

        // then
        assertThat(ok(panes.focus(P2)).tab().zoomed()).isEmpty();

        ok(panes.toggle());
        assertThat(ok(panes.split(Orientation.ROW)).tab().zoomed()).isEmpty();

        ok(panes.toggle());
        assertThat(ok(panes.close(P1)).tab().zoomed()).isEmpty();
    }

    @Test
    @DisplayName("줌한 창에 다시 포커스하면 줌도 그대로고 이벤트도 없다")
    void refocusZoomedPaneKeepsZoom() {
        // given
        givenTwoPanes();
        Workspace zoomed = ok(panes.toggle());
        events.clear();

        // when
        Workspace same = ok(panes.focus(P1));

        // then
        assertThat(same).isEqualTo(zoomed);
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("resize(RIGHT) 는 경계를 5% 옮기고 LayoutChanged, 그 방향 Split 이 없으면 오류 · 이벤트 없음")
    void resize() {
        // given
        givenTwoPanes();

        // when
        Workspace grown = ok(panes.resize(Direction.RIGHT));

        // then
        assertThat(((LayoutNode.Split) grown.tab().root()).ratio().value()).isCloseTo(0.55, within());
        assertThat(events).containsExactly(new AppEvent.LayoutChanged(grown));

        // when
        events.clear();
        LayoutError error = err(panes.resize(Direction.DOWN));

        // then
        assertThat(error).isEqualTo(new LayoutError.NoSplitInDirection(P1, Direction.DOWN));
        assertThat(workspace()).isEqualTo(grown);
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("경계가 이미 끝(0.9)이면 resize 는 성공하지만 바뀐 게 없어 이벤트가 없다")
    void resizeAtLimitIsNoOp() {
        // given
        givenTwoPanes();
        ok(panes.resizeTo(SplitPath.ROOT, new Ratio(Ratio.MAX)));
        events.clear();

        // when
        Workspace same = ok(panes.resize(Direction.RIGHT));

        // then
        assertThat(same).isEqualTo(workspace());
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("resizeTo 는 그 Split 의 비율을 바꾸고 LayoutChanged, 경로가 Split 이 아니면 UnknownSplit · 이벤트 없음")
    void resizeTo() {
        // given
        givenTwoPanes();

        // when
        Workspace dragged = ok(panes.resizeTo(SplitPath.ROOT, new Ratio(0.3)));

        // then
        assertThat(((LayoutNode.Split) dragged.tab().root()).ratio()).isEqualTo(new Ratio(0.3));
        assertThat(events).containsExactly(new AppEvent.LayoutChanged(dragged));

        // when
        events.clear();
        SplitPath leaf = SplitPath.of(SplitPath.Branch.SECOND);
        LayoutError error = err(panes.resizeTo(leaf, new Ratio(0.3)));

        // then
        assertThat(error).isEqualTo(new LayoutError.UnknownSplit(leaf));
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("swap(RIGHT) 는 이웃과 바꾸고 포커스는 옮겨 간 p1, 이웃이 없으면 NoNeighbor · 이벤트 없음")
    void swap() {
        // given
        givenTwoPanes();

        // when
        Workspace swapped = ok(panes.swap(Direction.RIGHT, AREA, GAP));

        // then
        assertThat(swapped.tab().root().panes()).containsExactly(P2, P1);
        assertThat(swapped.tab().focused()).isEqualTo(P1);
        assertThat(events).containsExactly(new AppEvent.LayoutChanged(swapped));

        // when: p1 은 이제 오른쪽 끝
        events.clear();
        LayoutError error = err(panes.swap(Direction.RIGHT, AREA, GAP));

        // then
        assertThat(error).isEqualTo(new LayoutError.NoNeighbor(P1, Direction.RIGHT));
        assertThat(workspace()).isEqualTo(swapped);
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("move(p2, Center(p1)) 는 교환, Side(p1, TOP) 은 p2 를 p1 위로 — 옮긴 창에 포커스, LayoutChanged 한 번씩")
    void move() {
        // given
        givenTwoPanes();

        // when
        Workspace swapped = ok(panes.move(P2, new DropZone.Center(P1)));

        // then
        assertThat(swapped.tab().root().panes()).containsExactly(P2, P1);
        assertThat(swapped.tab().focused()).isEqualTo(P2);

        // when
        Workspace above = ok(panes.move(P2, new DropZone.Side(P1, Edge.TOP)));

        // then
        assertThat(above.tab().root())
                .isEqualTo(new LayoutNode.Split(
                        Orientation.COLUMN, Ratio.HALF, new LayoutNode.Leaf(P2), new LayoutNode.Leaf(P1)));
        assertThat(above.tab().focused()).isEqualTo(P2);
        assertThat(events).containsExactly(new AppEvent.LayoutChanged(swapped), new AppEvent.LayoutChanged(above));
    }

    @Test
    @DisplayName("move 의 두 창이 같거나 없는 창이면 오류이고 상태 · 이벤트는 그대로다")
    void moveErrors() {
        // given
        givenTwoPanes();
        Workspace before = workspace();

        // when
        LayoutError same = err(panes.move(P1, new DropZone.Center(P1)));
        LayoutError unknown = err(panes.move(P3, new DropZone.Side(P1, Edge.LEFT)));

        // then
        assertThat(same).isEqualTo(new LayoutError.SamePane(P1));
        assertThat(unknown).isEqualTo(new LayoutError.UnknownPane(P3));
        assertThat(workspace()).isEqualTo(before);
        assertThat(events).isEmpty();
    }

    private static org.assertj.core.data.Offset<Double> within() {
        return org.assertj.core.data.Offset.offset(1e-9);
    }
}
