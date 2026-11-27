package tanager.domain.workspace;

/**
 * 앱 창의 화면 좌표와 크기(픽셀, FR-RES-03). 왼쪽 · 위 모니터에서는 좌표가 음수일 수 있다. 화면 밖인지는 UI 가 판정한다. 불변식: 너비 · 높이 ≥ 1.
 */
public record Bounds(int x, int y, int width, int height) {
    public Bounds {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("창 크기는 1 이상이어야 한다: " + width + "x" + height);
        }
    }
}
