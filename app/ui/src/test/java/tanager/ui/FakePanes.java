package tanager.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import tanager.application.port.in.ClosePaneUseCase;
import tanager.application.port.in.FocusPaneUseCase;
import tanager.application.port.in.MovePaneUseCase;
import tanager.application.port.in.ResizePaneUseCase;
import tanager.application.port.in.SplitPaneUseCase;
import tanager.application.port.in.SwapPaneUseCase;
import tanager.application.port.in.ZoomPaneUseCase;
import tanager.domain.layout.Direction;
import tanager.domain.layout.DropZone;
import tanager.domain.layout.LayoutError;
import tanager.domain.layout.Orientation;
import tanager.domain.layout.Ratio;
import tanager.domain.layout.Rect;
import tanager.domain.layout.SplitPath;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.Result;
import tanager.domain.workspace.Workspace;

/** 창 유스케이스들의 가짜. 부른 순서대로 호출을 글자로 남기고, {@link #reply} 를 돌려준다. */
final class FakePanes
        implements SplitPaneUseCase,
                ResizePaneUseCase,
                FocusPaneUseCase,
                ZoomPaneUseCase,
                SwapPaneUseCase,
                MovePaneUseCase,
                ClosePaneUseCase {
    final List<String> calls = new ArrayList<>();
    Result<Workspace, LayoutError> reply = Result.ok(Workspace.initial());

    PaneCommands commands() {
        return PaneCommands.of(this);
    }

    private CompletionStage<Result<Workspace, LayoutError>> record(String call) {
        calls.add(call);
        return CompletableFuture.completedFuture(reply);
    }

    @Override
    public CompletionStage<Result<Workspace, LayoutError>> split(Orientation orientation) {
        return record("split " + orientation);
    }

    @Override
    public CompletionStage<Result<Workspace, LayoutError>> resize(Direction direction) {
        return record("resize " + direction);
    }

    @Override
    public CompletionStage<Result<Workspace, LayoutError>> resizeTo(SplitPath path, Ratio ratio) {
        return record("resizeTo " + path + " " + ratio);
    }

    @Override
    public CompletionStage<Result<Workspace, LayoutError>> focus(PaneId pane) {
        return record("focus " + pane.value());
    }

    @Override
    public CompletionStage<Result<Workspace, LayoutError>> focus(Direction direction, Rect bounds, int gap) {
        return record("focus " + direction + " " + bounds + " " + gap);
    }

    @Override
    public CompletionStage<Result<Workspace, LayoutError>> toggle() {
        return record("zoom");
    }

    @Override
    public CompletionStage<Result<Workspace, LayoutError>> swap(Direction direction, Rect bounds, int gap) {
        return record("swap " + direction + " " + bounds + " " + gap);
    }

    @Override
    public CompletionStage<Result<Workspace, LayoutError>> move(PaneId pane, DropZone zone) {
        return record("move " + pane.value() + " " + zone);
    }

    @Override
    public CompletionStage<Result<Workspace, LayoutError>> close(PaneId pane) {
        return record("close " + pane.value());
    }
}
