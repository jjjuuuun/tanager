package tanager.ui;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import tanager.application.port.in.ClosePaneUseCase;
import tanager.application.port.in.FocusPaneUseCase;
import tanager.application.port.in.MovePaneUseCase;
import tanager.application.port.in.ResizePaneUseCase;
import tanager.application.port.in.SplitPaneUseCase;
import tanager.application.port.in.SwapPaneUseCase;
import tanager.application.port.in.ZoomPaneUseCase;
import tanager.domain.layout.DropZone;
import tanager.domain.layout.Edge;
import tanager.domain.layout.LayoutError;
import tanager.domain.layout.Orientation;
import tanager.domain.layout.Rect;
import tanager.domain.session.ShellProfile;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.Result;
import tanager.domain.workspace.Workspace;
import tanager.ui.keys.LayoutAction;

/**
 * 창 배치를 바꾸는 유스케이스 묶음(#148). 키보드 · 마우스가 같은 {@link #run} 으로 부른다(FR-LAY-09). 유스케이스는 상태 실행기에 넣기만 해서
 * EDT 에서 불러도 된다 — 결과는 {@code LayoutChanged} 로 화면에 온다.
 */
public record PaneCommands(
        SplitPaneUseCase split,
        ResizePaneUseCase resize,
        FocusPaneUseCase focus,
        ZoomPaneUseCase zoom,
        SwapPaneUseCase swap,
        MovePaneUseCase move,
        ClosePaneUseCase close) {
    public PaneCommands {
        Objects.requireNonNull(split, "split");
        Objects.requireNonNull(resize, "resize");
        Objects.requireNonNull(focus, "focus");
        Objects.requireNonNull(zoom, "zoom");
        Objects.requireNonNull(swap, "swap");
        Objects.requireNonNull(move, "move");
        Objects.requireNonNull(close, "close");
    }

    /** 모든 창 유스케이스를 구현한 하나(조립 루트의 {@code PaneManager})로 묶는다. */
    public static <
                    T extends
                            SplitPaneUseCase & ResizePaneUseCase & FocusPaneUseCase & ZoomPaneUseCase & SwapPaneUseCase
                                    & MovePaneUseCase & ClosePaneUseCase>
            PaneCommands of(T panes) {
        return new PaneCommands(panes, panes, panes, panes, panes, panes, panes);
    }

    /**
     * {@code action} 을 그 유스케이스로 부른다. 방향 포커스 · 교환은 화면에 그린 창 영역 {@code area} 와 간격 {@code gap} 으로 이웃을 찾고, 닫기는
     * {@code focused} 를 닫는다. 이웃이 없음 같은 실패는 결과 값으로 온다.
     */
    CompletionStage<Result<Workspace, LayoutError>> run(LayoutAction action, PaneId focused, Rect area, int gap) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(focused, "focused");
        Objects.requireNonNull(area, "area");
        return switch (action) {
            case LayoutAction.Split(var orientation) -> split.split(orientation);
            case LayoutAction.Focus(var direction) -> focus.focus(direction, area, gap);
            case LayoutAction.Resize(var direction) -> resize.resize(direction);
            case LayoutAction.Zoom() -> zoom.toggle();
            case LayoutAction.Close() -> close.close(focused);
            case LayoutAction.Swap(var direction) -> swap.swap(direction, area, gap);
        };
    }

    /**
     * 머리줄 버튼 · 메뉴(#149): {@code pane} 에 먼저 포커스하고 {@code action} 을 부른다. 닫기는 포커스를 옮기지 않고 그 창을 닫는다. 포커스가
     * 실패하면(그 사이 닫힌 창) 동작을 부르지 않는다.
     */
    CompletionStage<Result<Workspace, LayoutError>> runOn(PaneId pane, LayoutAction action, Rect area, int gap) {
        Objects.requireNonNull(pane, "pane");
        if (action instanceof LayoutAction.Close) {
            return close.close(pane);
        }
        return afterFocus(pane, _ -> run(action, pane, area, gap));
    }

    /**
     * {@code pane} 의 앞쪽(ROW 왼쪽, COLUMN 위)에 새 창을 연다(메뉴 ▸ 분할 ▸ 위 · 왼쪽). 분할은 뒤쪽에 새 창을 내므로, 포커스 → 분할 → 새 창을
     * {@code pane} 의 앞 변으로 옮긴다. 배치가 두 번 바뀌지만 새 창의 크기는 같아(반반) 셸 크기는 그대로다.
     */
    CompletionStage<Result<Workspace, LayoutError>> splitBefore(PaneId pane, Orientation orientation) {
        Objects.requireNonNull(orientation, "orientation");
        Edge edge = switch (orientation) {
            case ROW -> Edge.LEFT;
            case COLUMN -> Edge.TOP;
        };
        return afterFocus(
                pane,
                _ -> split.split(orientation)
                        .thenCompose(created -> then(
                                created, opened -> move.move(opened.tab().focused(), new DropZone.Side(pane, edge)))));
    }

    /** {@code pane} 오른쪽에 {@code shell} 셸로 새 창(메뉴 ▸ 다른 프로파일로 분할). */
    CompletionStage<Result<Workspace, LayoutError>> splitWith(PaneId pane, ShellProfile shell) {
        Objects.requireNonNull(shell, "shell");
        return afterFocus(pane, _ -> split.split(Orientation.ROW, shell));
    }

    private CompletionStage<Result<Workspace, LayoutError>> afterFocus(
            PaneId pane, Function<Workspace, CompletionStage<Result<Workspace, LayoutError>>> next) {
        Objects.requireNonNull(pane, "pane");
        return focus.focus(pane).thenCompose(focused -> then(focused, next));
    }

    private static CompletionStage<Result<Workspace, LayoutError>> then(
            Result<Workspace, LayoutError> previous,
            Function<Workspace, CompletionStage<Result<Workspace, LayoutError>>> next) {
        return previous.fold(
                next, error -> CompletableFuture.<Result<Workspace, LayoutError>>completedFuture(Result.err(error)));
    }
}
