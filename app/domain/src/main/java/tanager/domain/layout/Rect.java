package tanager.domain.layout;

/** 화면 좌표의 사각형(픽셀). UI 툴킷과 무관한 값이라 domain 에 둔다. */
public record Rect(int x, int y, int width, int height) {
    public Rect {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("크기는 음수일 수 없다: " + width + "x" + height);
        }
    }

    /** 점이 안에 있는가. 오른쪽 · 아래 변은 빼서 맞닿은 두 사각형이 한 점을 같이 갖지 않게 한다. */
    public boolean contains(int px, int py) {
        return px >= x && px < x + width && py >= y && py < y + height;
    }
}
