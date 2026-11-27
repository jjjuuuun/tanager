package tanager.domain.layout;

/** 창의 변. 끌어다 놓을 때 대상 창의 어느 쪽에 붙일지(FR-LAY-12). */
public enum Edge {
    TOP,
    BOTTOM,
    LEFT,
    RIGHT;

    /** 이 변에 붙이면 생기는 Split 의 방향. 위아래 변은 COLUMN, 좌우 변은 ROW. */
    public Orientation orientation() {
        return switch (this) {
            case TOP, BOTTOM -> Orientation.COLUMN;
            case LEFT, RIGHT -> Orientation.ROW;
        };
    }

    /** 위 · 왼쪽 변이면 true — 붙인 창이 Split 의 first 가 된다. */
    public boolean leading() {
        return switch (this) {
            case TOP, LEFT -> true;
            case BOTTOM, RIGHT -> false;
        };
    }
}
