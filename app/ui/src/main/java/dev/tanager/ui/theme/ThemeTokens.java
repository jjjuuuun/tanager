package dev.tanager.ui.theme;

import java.util.EnumSet;
import java.util.Map;

/** 한 테마의 토큰 12개 색. 모두 있어야 한다. */
record ThemeTokens(Map<ColorToken, Rgb> colors) {
    public ThemeTokens {
        colors = Map.copyOf(colors);
        EnumSet<ColorToken> missing = EnumSet.allOf(ColorToken.class);
        missing.removeAll(colors.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("빠진 토큰: " + missing);
        }
    }

    public Rgb get(ColorToken token) {
        return colors.get(token);
    }
}
