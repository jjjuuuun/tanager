package tanager.domain.layout;

/** 화면 좌표의 사각형(픽셀). UI 툴킷과 무관한 값이라 domain 에 둔다. */
public record Rect(int x, int y, int width, int height) {
    public Rect {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("크기는 음수일 수 없다: " + width + "x" + height);
        }
    }
}
