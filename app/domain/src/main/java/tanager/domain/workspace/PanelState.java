package tanager.domain.workspace;

/** 사이드바 · 도구함 패널의 표시 여부와 폭(픽셀, FR-RES-03). 숨겨도 폭을 기억해 다시 열 때 쓴다. 불변식: 폭 ≥ 0. */
public record PanelState(boolean visible, int width) {
    public PanelState {
        if (width < 0) {
            throw new IllegalArgumentException("패널 폭은 0 이상이어야 한다: " + width);
        }
    }
}
