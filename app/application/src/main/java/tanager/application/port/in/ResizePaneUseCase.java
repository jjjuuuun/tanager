package tanager.application.port.in;

import java.util.concurrent.CompletionStage;
import tanager.domain.layout.Direction;
import tanager.domain.layout.LayoutError;
import tanager.domain.layout.Ratio;
import tanager.domain.layout.SplitPath;
import tanager.domain.shared.Result;
import tanager.domain.workspace.Workspace;

/** 창 크기를 바꾼다 — 키보드(FR-LAY-02)와 경계선 드래그(FR-LAY-11)(#147). 바뀌면 {@code LayoutChanged} 가 한 번 나가고 줌은 풀린다. */
public interface ResizePaneUseCase {
    /**
     * 포커스 창을 감싼 가장 가까운 {@code direction} 축 경계를 그쪽으로 5% 옮긴다. 그런 Split 이 없으면 {@link
     * LayoutError.NoSplitInDirection}. 이미 끝(0.1 · 0.9)이면 성공하지만 바뀐 게 없어 이벤트도 없다.
     */
    CompletionStage<Result<Workspace, LayoutError>> resize(Direction direction);

    /** {@code path} 의 Split 비율을 {@code ratio} 로 바꾼다. Split 이 아니면 {@link LayoutError.UnknownSplit}. */
    CompletionStage<Result<Workspace, LayoutError>> resizeTo(SplitPath path, Ratio ratio);
}
