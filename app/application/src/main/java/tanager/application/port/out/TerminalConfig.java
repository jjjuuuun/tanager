package tanager.application.port.out;

import java.util.Objects;

/**
 * 터미널 설정(config {@code terminal}, FR-CFG-02). 불변식: 글꼴 이름이 비어 있지 않고, size 8~32, lineHeight 1.0~2.0, scrollback
 * 0~100000. 저장소는 읽을 때 범위 밖 값을 경계로 고정하고 경고한다.
 *
 * @param font 글꼴 이름
 * @param size 글자 크기(pt)
 * @param lineHeight 줄 높이 배수
 * @param scrollback 화면 밖으로 보관할 줄 수
 */
public record TerminalConfig(String font, int size, double lineHeight, int scrollback) {
    public static final int MIN_SIZE = 8;
    public static final int MAX_SIZE = 32;
    public static final double MIN_LINE_HEIGHT = 1.0;
    public static final double MAX_LINE_HEIGHT = 2.0;
    public static final int MIN_SCROLLBACK = 0;
    public static final int MAX_SCROLLBACK = 100_000;

    private static final TerminalConfig DEFAULTS = new TerminalConfig("D2Coding", 14, 1.2, 10_000);

    public TerminalConfig {
        Objects.requireNonNull(font, "font");
        if (font.isBlank()) {
            throw new IllegalArgumentException("글꼴 이름이 비어 있다");
        }
        Ranges.require("size", size, MIN_SIZE, MAX_SIZE);
        Ranges.require("lineHeight", lineHeight, MIN_LINE_HEIGHT, MAX_LINE_HEIGHT);
        Ranges.require("scrollback", scrollback, MIN_SCROLLBACK, MAX_SCROLLBACK);
    }

    /** SDD §9.3 의 기본값: D2Coding · 14 · 1.2 · 10000. */
    public static TerminalConfig defaults() {
        return DEFAULTS;
    }
}
