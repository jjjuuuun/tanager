package dev.tanager.ui.theme;

import java.util.Locale;

/** SDD §8.5 의 색 토큰 12개. UI 코드는 이 토큰으로만 색을 얻는다({@link ThemeColors}). */
public enum ColorToken {
    WINDOW,
    SIDEBAR,
    TERM,
    HEADER,
    BORDER,
    TEXT,
    MUTED,
    ACCENT,
    SELECT,
    OK,
    WARN,
    BAD;

    private String lowerName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** UIManager 키, 예 {@code "Tanager.window"}. */
    public String key() {
        return "Tanager." + lowerName();
    }

    /** 테마 파일의 변수 이름, 예 {@code "@window"}. */
    String variable() {
        return "@" + lowerName();
    }
}
