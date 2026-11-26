package dev.tanager.archfixture.ui.theme;

/** 픽스처: 실제 dev.tanager.ui.theme.Rgb 와 같은 자리(ui.theme)의 색 값. */
public record Rgb(int red, int green, int blue) {
    public static Rgb parse(String hex) {
        int value = Integer.parseInt(hex.substring(1), 16);
        return new Rgb((value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF);
    }
}
