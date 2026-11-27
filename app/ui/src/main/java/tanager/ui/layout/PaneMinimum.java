package tanager.ui.layout;

/**
 * 창 하나의 최소 크기(픽셀). 경계선을 끌어도 창이 이보다 작아지지 않는다(#150, FR-LAY-11 — 20열 × 5행을 글꼴 크기로 바꾼 값).
 *
 * @param width 최소 폭
 * @param height 최소 높이
 */
public record PaneMinimum(int width, int height) {
    public PaneMinimum {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("최소 크기는 음수일 수 없다: " + width + "x" + height);
        }
    }
}
