package dev.tanager.ui.theme;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import javax.swing.UIDefaults;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** FlatLaf 는 잘못된 값이면 로그만 남기고 null 을 주므로, LaF 가 만든 기본값을 직접 확인한다(headless). */
class SoftLafDefaultsTest {
    private static UIDefaults defaults(SoftTheme theme) {
        return switch (theme) {
            case DARK -> new SoftDarkLaf().getDefaults();
            case LIGHT -> new SoftLightLaf().getDefaults();
        };
    }

    private static Rgb rgb(Color c) {
        return new Rgb(c.getRed(), c.getGreen(), c.getBlue());
    }

    @ParameterizedTest
    @EnumSource(SoftTheme.class)
    @DisplayName("토큰 키 12개가 null 이 아니고 테마 파일 값과 같다")
    void tokenKeysMatchFile(SoftTheme theme) {
        // given
        UIDefaults d = defaults(theme);
        ThemeTokens expected = theme.load().tokens();

        // then
        for (ColorToken t : ColorToken.values()) {
            Color c = d.getColor(t.key());
            assertThat(c).as(t.key()).isNotNull();
            assertThat(rgb(c)).as(t.key()).isEqualTo(expected.get(t));
        }
    }

    @ParameterizedTest
    @EnumSource(SoftTheme.class)
    @DisplayName("터미널 색 키(전경·배경·커서·선택·ansi 16)가 null 이 아니고 팔레트와 같다")
    void terminalKeysMatchPalette(SoftTheme theme) {
        UIDefaults d = defaults(theme);
        TerminalPalette p = theme.load().terminal();

        assertThat(rgb(d.getColor("Tanager.term.foreground"))).isEqualTo(p.foreground());
        assertThat(rgb(d.getColor("Tanager.term.background"))).isEqualTo(p.background());
        assertThat(rgb(d.getColor("Tanager.term.cursor"))).isEqualTo(p.cursor());
        assertThat(rgb(d.getColor("Tanager.term.selection"))).isEqualTo(p.selection());
        for (int i = 0; i < 16; i++) {
            Color c = d.getColor("Tanager.term.ansi." + i);
            assertThat(c).as("ansi " + i).isNotNull();
            assertThat(rgb(c)).as("ansi " + i).isEqualTo(p.ansi(i));
        }
    }

    @ParameterizedTest
    @EnumSource(SoftTheme.class)
    @DisplayName("모서리 반지름 · 기반 색 · 포커스 색이 설계대로다")
    void shapeAndBaseColors(SoftTheme theme) {
        UIDefaults d = defaults(theme);
        ThemeTokens t = theme.load().tokens();

        assertThat(d.getInt("Component.arc")).isEqualTo(8);
        assertThat(d.getInt("Button.arc")).isEqualTo(8);
        assertThat(d.getInt("Tanager.pane.arc")).isEqualTo(6);
        assertThat(rgb(d.getColor("Panel.background"))).isEqualTo(t.get(ColorToken.WINDOW));
        assertThat(rgb(d.getColor("Component.focusColor"))).isEqualTo(t.get(ColorToken.ACCENT));
        // 시안 D: 제목 표시줄은 본문(window)보다 한 단 어두운 header 색이다 — FlatLaf 기본 unifiedBackground(true)를 끈다
        assertThat(d.getBoolean("TitlePane.unifiedBackground")).isFalse();
        assertThat(rgb(d.getColor("TitlePane.background"))).isEqualTo(t.get(ColorToken.HEADER));
    }

    @Test
    @DisplayName("ThemeColors 는 UIManager 의 토큰 색을 돌려준다")
    void themeColorsReadsUiManager() {
        // given
        javax.swing.UIManager.put(ColorToken.ACCENT.key(), new Color(1, 2, 3));

        // when / then
        assertThat(ThemeColors.get(ColorToken.ACCENT)).isEqualTo(new Color(1, 2, 3));
    }
}
