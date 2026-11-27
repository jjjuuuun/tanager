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

    /** 그 창을 감싼 Split 중 그 방향으로 경계를 옮길 수 있는 것이 없다(예: 위아래로만 나뉜 트리를 좌우로). */
    record NoSplitInDirection(PaneId pane, Direction direction) implements LayoutError {
        public NoSplitInDirection {
            Objects.requireNonNull(pane, "pane");
            Objects.requireNonNull(direction, "direction");
        }
    }

    /** 경로가 Split 을 가리키지 않는다(창이거나 트리 밖) — 드래그하는 사이 트리가 바뀌었을 때 생긴다. */
    record UnknownSplit(SplitPath path) implements LayoutError {
        public UnknownSplit {
            Objects.requireNonNull(path, "path");
        }
    }

    /** 교환 · 이동의 두 창이 같다. */
    record SamePane(PaneId pane) implements LayoutError {
        public SamePane {
            Objects.requireNonNull(pane, "pane");
        }
    }
}
