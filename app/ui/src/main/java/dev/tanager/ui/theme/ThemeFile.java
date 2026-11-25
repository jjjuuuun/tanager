package dev.tanager.ui.theme;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 테마 파일(themes/soft-*.properties)의 색 변수. FlatLaf 없이 토큰 · 팔레트를 읽어 대비를 검사하려고 둔다.
 *
 * @param variables {@code "@이름"} → 값({@code "#RRGGBB"} 또는 {@code "@다른이름"})
 */
record ThemeFile(Map<String, String> variables) {
    public ThemeFile {
        variables = Map.copyOf(variables);
    }

    /** {@code @이름 = 값} 줄만 읽는다. 주석 · 빈 줄 · 컴포넌트 키는 무시한다. */
    public static ThemeFile parse(String text) {
        Map<String, String> variables = new HashMap<>();
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            int eq = line.indexOf('=');
            if (line.startsWith("@") && eq > 0) {
                variables.put(
                        line.substring(0, eq).strip(), line.substring(eq + 1).strip());
            }
        }
        return new ThemeFile(variables);
    }

    /** 변수의 색. {@code @참조}를 따라가며, 정의되지 않았거나 순환하면 {@link IllegalArgumentException}. */
    public Rgb resolve(String variable) {
        Set<String> seen = new LinkedHashSet<>();
        String name = variable;
        while (true) {
            if (!seen.add(name)) {
                throw new IllegalArgumentException("순환 참조: " + String.join(" → ", seen) + " → " + name);
            }
            String value = variables.get(name);
            if (value == null) {
                throw new IllegalArgumentException("정의되지 않은 변수: " + name);
            }
            if (!value.startsWith("@")) {
                return Rgb.parse(value);
            }
            name = value;
        }
    }

    /** 토큰 12개. */
    public ThemeTokens tokens() {
        Map<ColorToken, Rgb> colors = new EnumMap<>(ColorToken.class);
        for (ColorToken token : ColorToken.values()) {
            colors.put(token, resolve(token.variable()));
        }
        return new ThemeTokens(colors);
    }

    /** 터미널 팔레트. 배경은 {@code @term} 이다. */
    public TerminalPalette terminal() {
        List<Rgb> ansi = new ArrayList<>();
        for (int i = 0; i < TerminalPalette.ANSI_COLORS; i++) {
            ansi.add(resolve("@ansi" + i));
        }
        return new TerminalPalette(
                resolve("@termFg"),
                resolve(ColorToken.TERM.variable()),
                resolve("@termCursor"),
                resolve("@termSelect"),
                ansi);
    }
}
