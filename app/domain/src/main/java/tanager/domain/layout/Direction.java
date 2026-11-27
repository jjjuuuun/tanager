package tanager.domain.layout;

/**
 * 화면 방향. 방향 포커스 이동과 키보드 크기 조절(FR-LAY-02 · 06)이 쓴다. 크기 조절에서는 "경계선이 움직이는 쪽"이다 — 어느 창에서 눌러도
 * RIGHT 면 경계가 오른쪽으로 간다.
 */
public enum Direction {
    LEFT,
    RIGHT,
    UP,
    DOWN;

    /** 이 방향으로 움직이는 경계선을 가진 Split 의 방향. 좌우는 ROW, 위아래는 COLUMN. */
    public Orientation axis() {
        return switch (this) {
            case LEFT, RIGHT -> Orientation.ROW;
            case UP, DOWN -> Orientation.COLUMN;
        };
    }

    /** 오른쪽 · 아래(좌표가 커지는 쪽)면 true. 그쪽으로 경계가 가면 first 가 커진다. */
    public boolean forward() {
        return switch (this) {
            case RIGHT, DOWN -> true;
            case LEFT, UP -> false;
        };
    }
}
