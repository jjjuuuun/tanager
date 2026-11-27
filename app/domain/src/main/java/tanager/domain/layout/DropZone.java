package tanager.domain.layout;

import java.util.Objects;
import tanager.domain.shared.PaneId;

/** 창을 끌어다 놓을 곳(FR-LAY-12). 가장자리 띠는 그 변에 붙이고({@link LayoutNode#move}), 가운데는 두 창을 바꾼다({@link LayoutNode#swap}). */
public sealed interface DropZone {

    /** 놓으면 바뀌는 창. */
    PaneId target();

    /** 대상 창의 {@code edge} 쪽에 붙인다. */
    record Side(PaneId target, Edge edge) implements DropZone {
        public Side {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(edge, "edge");
        }
    }

    /** 대상 창과 자리를 바꾼다. */
    record Center(PaneId target) implements DropZone {
        public Center {
            Objects.requireNonNull(target, "target");
        }
    }
}
