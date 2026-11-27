package tanager.ui.theme;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import javax.swing.LookAndFeel;
import javax.swing.UIManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ColorMixTest {
    private static final Color FROM = new Color(0x26, 0x2A, 0x35);
    private static final Color TO = new Color(0x7A, 0xA2, 0xF7);

    @Test
    @DisplayName("섞기는 0 이면 시작 색, 1 이면 끝 색이다")
    void endpoints() {
        // when / then
        assertThat(ThemeColors.mix(FROM, TO, 0)).isEqualTo(FROM);
        assertThat(ThemeColors.mix(FROM, TO, 1)).isEqualTo(TO);
    }

    @Test
    @DisplayName("섞기는 채널마다 보간하고 반올림한다")
    void interpolatesChannels() {
        // given
        Color black = new Color(0, 0, 0);
        Color white = new Color(255, 255, 255);

        // when
        Color middle = ThemeColors.mix(black, white, 0.5);

        // then
        assertThat(middle).isEqualTo(new Color(128, 128, 128));
    }

    @Test
    @DisplayName("범위 밖 비율은 0..1 로 자른다")
    void clampsFraction() {
        // when / then
        assertThat(ThemeColors.mix(FROM, TO, -1)).isEqualTo(FROM);
        assertThat(ThemeColors.mix(FROM, TO, 3)).isEqualTo(TO);
    }

    @Test
    @DisplayName("토큰 두 개를 섞으면 설치된 테마의 두 색 사이 값이다")
    void mixesTokens() throws Exception {
        // given
        LookAndFeel previous = UIManager.getLookAndFeel();
        UIManager.setLookAndFeel(new SoftDarkLaf());
        try {
            // when
            Color start = ThemeColors.mix(ColorToken.BORDER, ColorToken.ACCENT, 0);
            Color end = ThemeColors.mix(ColorToken.BORDER, ColorToken.ACCENT, 1);

            // then
            assertThat(start)
                    .isEqualTo(new Color(ThemeColors.get(ColorToken.BORDER).getRGB()));
            assertThat(end)
                    .isEqualTo(new Color(ThemeColors.get(ColorToken.ACCENT).getRGB()));
        } finally {
            UIManager.setLookAndFeel(previous);
        }
    }
}
