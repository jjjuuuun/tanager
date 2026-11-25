package dev.tanager.ui.theme;

import java.util.regex.Pattern;

/** 화면과 무관한 sRGB 색 값. 대비 계산과 테마 파일 검증에 쓴다. */
public record Rgb(int red, int green, int blue) {
    private static final Pattern HEX = Pattern.compile("#[0-9A-Fa-f]{6}");

    public Rgb {
        checkChannel("red", red);
        checkChannel("green", green);
        checkChannel("blue", blue);
    }

    private static void checkChannel(String name, int value) {
        if (value < 0 || value > 255) {
            throw new IllegalArgumentException(name + " 는 0..255 여야 한다: " + value);
        }
    }

    /** {@code "#RRGGBB"}(대소문자 무시)만 받는다. 그 밖은 {@link IllegalArgumentException}. */
    public static Rgb parse(String hex) {
        if (!HEX.matcher(hex).matches()) {
            throw new IllegalArgumentException("#RRGGBB 형식이 아니다: " + hex);
        }
        int value = Integer.parseInt(hex.substring(1), 16);
        return new Rgb((value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF);
    }

    /** WCAG 2.x 상대 휘도(0 = 검정, 1 = 흰색). */
    public double relativeLuminance() {
        return 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue);
    }

    private static double linear(int channel) {
        double c = channel / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
