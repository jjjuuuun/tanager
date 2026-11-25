package dev.tanager.ui.theme;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** SDD §8.5 의 토큰 값과 대비 기준(색의 원본은 themes/soft-*.properties 하나). */
class SoftThemeTokensTest {
    private static final List<ColorToken> BACKGROUNDS =
            List.of(ColorToken.WINDOW, ColorToken.SIDEBAR, ColorToken.TERM, ColorToken.HEADER, ColorToken.SELECT);
    private static final List<ColorToken> ACCENTS =
            List.of(ColorToken.MUTED, ColorToken.ACCENT, ColorToken.OK, ColorToken.WARN, ColorToken.BAD);

    @ParameterizedTest(name = "{0} {1} = {2}")
    @DisplayName("테마 파일의 토큰 값이 SDD 표와 같다")
    @CsvSource({
        "DARK, WINDOW, #1B1E26",
        "DARK, SIDEBAR, #181B23",
        "DARK, TERM, #1B1E26",
        "DARK, HEADER, #15181F",
        "DARK, BORDER, #262A35",
        "DARK, TEXT, #D6DAE3",
        "DARK, MUTED, #9AA1AF",
        "DARK, ACCENT, #7AA2F7",
        "DARK, SELECT, #252A36",
        "DARK, OK, #7FD1A8",
        "DARK, WARN, #E6C07B",
        "DARK, BAD, #F2A7A7",
        "LIGHT, WINDOW, #F1EEE8",
        "LIGHT, SIDEBAR, #F7F5F0",
        "LIGHT, TERM, #FBFAF7",
        "LIGHT, HEADER, #E8E4DC",
        "LIGHT, BORDER, #E4E0D8",
        "LIGHT, TEXT, #34322E",
        "LIGHT, MUTED, #625E57",
        "LIGHT, ACCENT, #2F6B5C",
        "LIGHT, SELECT, #E3EEEA",
        "LIGHT, OK, #2A6B4A",
        "LIGHT, WARN, #80581A",
        "LIGHT, BAD, #984545"
    })
    void tokenValuesMatchSdd(SoftTheme theme, ColorToken token, String hex) {
        // given / when
        ThemeTokens tokens = theme.load().tokens();

        // then
        assertThat(tokens.get(token)).isEqualTo(Rgb.parse(hex));
    }

    @ParameterizedTest
    @EnumSource(SoftTheme.class)
    @DisplayName("토큰은 12개 모두 있다")
    void allTwelveTokensPresent(SoftTheme theme) {
        assertThat(theme.load().tokens().colors()).hasSize(12).containsKeys(ColorToken.values());
    }

    @ParameterizedTest
    @EnumSource(SoftTheme.class)
    @DisplayName("본문 글자색은 다섯 배경 위에서 대비 7~13 이다")
    void textContrastOnBackgrounds(SoftTheme theme) {
        ThemeTokens t = theme.load().tokens();

        for (ColorToken bg : BACKGROUNDS) {
            assertThat(Contrast.ratio(t.get(ColorToken.TEXT), t.get(bg)))
                    .as("%s text on %s", theme, bg)
                    .isBetween(7.0, 13.0);
        }
    }

    @ParameterizedTest
    @EnumSource(SoftTheme.class)
    @DisplayName("muted · accent · ok · warn · bad 는 다섯 배경 위에서 대비 4.5 이상이다")
    void accentContrastOnBackgrounds(SoftTheme theme) {
        ThemeTokens t = theme.load().tokens();

        for (ColorToken fg : ACCENTS) {
            for (ColorToken bg : BACKGROUNDS) {
                assertThat(Contrast.ratio(t.get(fg), t.get(bg)))
                        .as("%s %s on %s", theme, fg, bg)
                        .isGreaterThanOrEqualTo(4.5);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(SoftTheme.class)
    @DisplayName("순수 검정 · 순수 흰색 토큰은 없다")
    void noPureBlackOrWhite(SoftTheme theme) {
        Stream<Rgb> all = theme.load().tokens().colors().values().stream();

        assertThat(all).doesNotContain(Rgb.parse("#000000"), Rgb.parse("#FFFFFF"));
    }

    @ParameterizedTest(name = "{0} {1} = {2}")
    @DisplayName("터미널 팔레트 값이 설계와 같다")
    @CsvSource({
        "DARK, foreground, #D6DAE3",
        "DARK, cursor, #C8D3F5",
        "DARK, selection, #2D3650",
        "DARK, ansi0, #2A2F3B",
        "DARK, ansi1, #E08A8A",
        "DARK, ansi2, #8CC6A0",
        "DARK, ansi3, #D9BC7E",
        "DARK, ansi4, #7AA2F7",
        "DARK, ansi5, #BB9AE0",
        "DARK, ansi6, #7FC4CC",
        "DARK, ansi7, #B4BAC6",
        "DARK, ansi8, #6E7686",
        "DARK, ansi9, #F2A7A7",
        "DARK, ansi10, #B8E6C6",
        "DARK, ansi11, #E9D09A",
        "DARK, ansi12, #A3BEF9",
        "DARK, ansi13, #D3B9F0",
        "DARK, ansi14, #A2D8DE",
        "DARK, ansi15, #DDE1E8",
        "LIGHT, foreground, #34322E",
        "LIGHT, cursor, #2F6B5C",
        "LIGHT, selection, #D3E3DC",
        "LIGHT, ansi0, #3A3835",
        "LIGHT, ansi1, #B04848",
        "LIGHT, ansi2, #2A5F3E",
        "LIGHT, ansi3, #7A5A14",
        "LIGHT, ansi4, #2F5C9E",
        "LIGHT, ansi5, #7D4A94",
        "LIGHT, ansi6, #226A73",
        "LIGHT, ansi7, #8A857C",
        "LIGHT, ansi8, #6F6A62",
        "LIGHT, ansi9, #8C3535",
        "LIGHT, ansi10, #3A7650",
        "LIGHT, ansi11, #8F6B1D",
        "LIGHT, ansi12, #3E6DB3",
        "LIGHT, ansi13, #9259AA",
        "LIGHT, ansi14, #2E7D87",
        "LIGHT, ansi15, #C9C4BA"
    })
    void paletteValues(SoftTheme theme, String slot, String hex) {
        TerminalPalette p = theme.load().terminal();
        Rgb expected = Rgb.parse(hex);

        Rgb actual = switch (slot) {
            case "foreground" -> p.foreground();
            case "cursor" -> p.cursor();
            case "selection" -> p.selection();
            default -> p.ansi(Integer.parseInt(slot.substring(4)));
        };
        assertThat(actual).isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(SoftTheme.class)
    @DisplayName("터미널 팔레트는 ansi 16색이고 배경은 테마의 term 이다")
    void paletteShape(SoftTheme theme) {
        ThemeFile file = theme.load();

        assertThat(file.terminal().ansi()).hasSize(16);
        assertThat(file.terminal().background()).isEqualTo(file.tokens().get(ColorToken.TERM));
    }

    @ParameterizedTest
    @EnumSource(SoftTheme.class)
    @DisplayName("터미널 팔레트가 대비 기준을 모두 만족한다")
    void paletteContrast(SoftTheme theme) {
        // given
        TerminalPalette p = theme.load().terminal();
        Rgb bg = p.background();
        boolean dark = theme.dark();
        int bgEnd = dark ? 0 : 15; // 배경 쪽 끝 색: 검사 제외
        int bgSide = dark ? 8 : 7; // 배경 쪽 보조: >= 3.0
        List<Integer> opposite = dark ? List.of(7, 15) : List.of(0, 8);

        // then
        for (int i = 0; i < 16; i++) {
            if (i == bgEnd) {
                continue;
            }
            double ratio = Contrast.ratio(p.ansi(i), bg);
            assertThat(ratio).as("%s ansi%d <= 13", theme, i).isLessThanOrEqualTo(13.0);
            boolean colored = (i >= 1 && i <= 6) || (i >= 9 && i <= 14);
            if (colored) {
                assertThat(ratio).as("%s ansi%d >= 4.5", theme, i).isGreaterThanOrEqualTo(4.5);
            }
            if (i == bgSide) {
                assertThat(ratio).as("%s ansi%d >= 3.0", theme, i).isGreaterThanOrEqualTo(3.0);
            }
            if (opposite.contains(i)) {
                assertThat(ratio).as("%s ansi%d 4.5..13", theme, i).isBetween(4.5, 13.0);
            }
        }
        assertThat(Contrast.ratio(p.foreground(), bg)).as("foreground").isBetween(7.0, 13.0);
        assertThat(Contrast.ratio(p.cursor(), bg)).as("cursor").isGreaterThanOrEqualTo(4.5);
        assertThat(Contrast.ratio(p.selection(), bg)).as("selection vs bg").isGreaterThanOrEqualTo(1.25);
        assertThat(Contrast.ratio(p.foreground(), p.selection()))
                .as("foreground on selection")
                .isGreaterThanOrEqualTo(7.0);
    }

    @ParameterizedTest
    @EnumSource(SoftTheme.class)
    @DisplayName("빨강과 초록, 일반색과 밝은색은 서로 구분된다")
    void paletteSeparation(SoftTheme theme) {
        TerminalPalette p = theme.load().terminal();

        assertThat(Contrast.ratio(p.ansi(1), p.ansi(2))).as("red/green").isGreaterThanOrEqualTo(1.25);
        assertThat(Contrast.ratio(p.ansi(9), p.ansi(10))).as("bright red/green").isGreaterThanOrEqualTo(1.25);
        for (int i = 1; i <= 6; i++) {
            assertThat(Contrast.ratio(p.ansi(i), p.ansi(i + 8)))
                    .as("%s ansi%d vs ansi%d", theme, i, i + 8)
                    .isGreaterThanOrEqualTo(1.15);
        }
    }

    @Test
    @DisplayName("SoftTheme 은 리소스 경로와 다크 여부를 안다")
    void themeMetadata() {
        assertThat(SoftTheme.DARK.resource()).isEqualTo("themes/soft-dark.properties");
        assertThat(SoftTheme.LIGHT.resource()).isEqualTo("themes/soft-light.properties");
        assertThat(SoftTheme.DARK.dark()).isTrue();
        assertThat(SoftTheme.LIGHT.dark()).isFalse();
        assertThat(ColorToken.WINDOW.key()).isEqualTo("Tanager.window");
    }
}
