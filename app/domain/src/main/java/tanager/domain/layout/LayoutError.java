package tanager.domain.layout;

import java.util.Objects;
import tanager.domain.shared.PaneId;

/** 레이아웃을 바꿀 수 없는 이유. */
public sealed interface LayoutError {

    /** 트리에 그 창이 없다. */
    record UnknownPane(PaneId pane) implements LayoutError {
        public UnknownPane {
            Objects.requireNonNull(pane, "pane");
        }
    }

    /** 새로 만들 창 ID 가 이미 트리에 있다. */
    record DuplicatePane(PaneId pane) implements LayoutError {
        public DuplicatePane {
            Objects.requireNonNull(pane, "pane");
        }
    }

    /** 마지막 창은 닫을 수 없다(창을 닫으면 앱이 끝나는 일은 UI 가 정한다). */
    record LastPane(PaneId pane) implements LayoutError {
        public LastPane {
            Objects.requireNonNull(pane, "pane");
        }
    }
}
