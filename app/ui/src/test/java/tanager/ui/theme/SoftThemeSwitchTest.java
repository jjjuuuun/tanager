package tanager.ui.theme;

import static org.assertj.core.api.Assertions.assertThat;

import com.jediterm.core.Color;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.model.StyleState;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.out.Theme;

/** 정한 테마(다크 · 라이트)에서 LaF · 터미널 색 한 벌을 고르고, 이미 그린 글자가 새 테마 색을 따르게 한다(#185, FR-UI-05). */
class SoftThemeSwitchTest {
    private static Rgb rgb(Color c) {
        return new Rgb(c.getRed(), c.getGreen(), c.getBlue());
    }

    @Test
    @DisplayName("다크는 SoftDarkLaf, 라이트는 SoftLightLaf 를 고른다")
    void resolvedThemePicksLookAndFeel() {
        // when / then
        assertThat(SoftTheme.of(Theme.DARK)).isEqualTo(SoftTheme.DARK);
        assertThat(SoftTheme.of(Theme.LIGHT)).isEqualTo(SoftTheme.LIGHT);
        assertThat(SoftTheme.DARK.lookAndFeel()).isInstanceOf(SoftDarkLaf.class);
        assertThat(SoftTheme.LIGHT.lookAndFeel()).isInstanceOf(SoftLightLaf.class);
    }

    @Test
    @DisplayName("테마별 터미널 색 한 벌은 그 테마 파일의 팔레트(@ansi* · @termFg · @term)다 — UIManager 없이")
    void terminalColorsComeFromThemeFile() {
        // given
        TerminalPalette light = SoftTheme.LIGHT.load().terminal();

        // when
        JediTermColors colors = JediTermColors.of(SoftTheme.LIGHT);

        // then
        assertThat(rgb(colors.foreground().toColor())).isEqualTo(light.foreground());
        assertThat(rgb(colors.background().toColor())).isEqualTo(light.background());
        for (int i = 0; i < TerminalPalette.ANSI_COLORS; i++) {
            assertThat(rgb(colors.palette().getForeground(TerminalColor.index(i))))
                    .as("ansi %d", i)
                    .isEqualTo(light.ansi(i));
        }
    }

    @Test
    @DisplayName("기본 스타일의 전경 · 배경은 그릴 때 지금 테마 색을 읽는다 — 리셋(SGR 0) 뒤에 쓴 글자도 테마를 바꾸면 따라온다")
    void defaultStyleFollowsCurrentTheme() {
        // given
        AtomicReference<JediTermColors> live = new AtomicReference<>(JediTermColors.of(SoftTheme.DARK));
        StyleState state = JediTermColors.styleState(live::get);
        state.reset();
        TextStyle written = state.getCurrent();

        // when
        live.set(JediTermColors.of(SoftTheme.LIGHT));

        // then
        TerminalPalette light = SoftTheme.LIGHT.load().terminal();
        assertThat(rgb(written.getForeground().toColor())).isEqualTo(light.foreground());
        assertThat(rgb(written.getBackground().toColor())).isEqualTo(light.background());
        assertThat(rgb(state.getDefaultForeground().toColor())).isEqualTo(light.foreground());
    }

    @Test
    @DisplayName("읽히지 않는 ANSI 조합의 보정은 지금 테마의 팔레트로 판단한다")
    void readableCorrectionUsesCurrentPalette() {
        // given: 파랑 글자 + 초록 배경(GNU ls 의 ow=34;42)
        AtomicReference<JediTermColors> live = new AtomicReference<>(JediTermColors.of(SoftTheme.DARK));
        StyleState state = JediTermColors.styleState(live::get);
        TextStyle blueOnGreen = new TextStyle(TerminalColor.index(4), TerminalColor.index(2));
        TerminalPalette light = SoftTheme.LIGHT.load().terminal();

        // when
        live.set(JediTermColors.of(SoftTheme.LIGHT));
        state.setCurrent(blueOnGreen);

        // then: 라이트 팔레트로 본 보정값이다
        TerminalColor shown = state.getCurrent().getForeground();
        Rgb expected = ReadableForeground.on(light.ansi(4), light.ansi(2));
        Rgb actual = shown.isIndexed() ? light.ansi(shown.getColorIndex()) : rgb(shown.toColor());
        assertThat(actual).isEqualTo(expected);
    }
}
