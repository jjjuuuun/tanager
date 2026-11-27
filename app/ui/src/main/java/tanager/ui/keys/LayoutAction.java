package tanager.ui.keys;

import java.util.Objects;
import tanager.domain.layout.Direction;
import tanager.domain.layout.Orientation;

/** 키보드 · 마우스가 부르는 레이아웃 조작 하나(SDD §8.2, FR-LAY-01~06). 대상은 포커스 창이다. */
public sealed interface LayoutAction extends KeyAction {

    /** 포커스 창을 나눈다 — ROW 오른쪽, COLUMN 아래. */
    record Split(Orientation orientation) implements LayoutAction {
        public Split {
            Objects.requireNonNull(orientation, "orientation");
        }
    }

    /** 그 방향의 이웃 창으로 포커스를 옮긴다. */
    record Focus(Direction direction) implements LayoutAction {
        public Focus {
            Objects.requireNonNull(direction, "direction");
        }
    }

    /** 포커스 창의 경계를 그 방향으로 5% 옮긴다. */
    record Resize(Direction direction) implements LayoutAction {
        public Resize {
            Objects.requireNonNull(direction, "direction");
        }
    }

    /** 줌 토글. */
    record Zoom() implements LayoutAction {}

    /** 포커스 창을 닫는다(실행 중인 셸도 확인 없이 닫는다). */
    record Close() implements LayoutAction {}

    /** 포커스 창을 그 방향의 이웃과 바꾼다. */
    record Swap(Direction direction) implements LayoutAction {
        public Swap {
            Objects.requireNonNull(direction, "direction");
        }
    }
}
