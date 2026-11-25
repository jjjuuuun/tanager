package dev.tanager.ui.theme;

import static org.assertj.core.api.Assertions.assertThat;

import com.jediterm.core.Color;
import com.jediterm.terminal.TerminalColor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JediTermColorsTest {
    private static Rgb rgb(Color c) {
        return new Rgb(c.getRed(), c.getGreen(), c.getBlue());
    }

    @Test
    @DisplayName("팔레트의 ANSI 16색과 기본 전경·배경·커서·선택색이 그대로 JediTerm 색이 된다")
    void mapsPaletteOneToOne() {
        // given
        List<Rgb> ansi = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            ansi.add(new Rgb(i * 10, 200 - i * 5, 30 + i));
        }
        TerminalPalette palette =
                new TerminalPalette(new Rgb(1, 2, 3), new Rgb(4, 5, 6), new Rgb(7, 8, 9), new Rgb(10, 11, 12), ansi);

        // when
        JediTermColors colors = JediTermColors.from(palette);

        // then
        for (int i = 0; i < 16; i++) {
            TerminalColor index = TerminalColor.index(i);
            assertThat(rgb(colors.palette().getForeground(index)))
                    .as("전경 %d", i)
                    .isEqualTo(ansi.get(i));
            assertThat(rgb(colors.palette().getBackground(index)))
                    .as("배경 %d", i)
                    .isEqualTo(ansi.get(i));
        }
        assertThat(rgb(colors.foreground().toColor())).isEqualTo(new Rgb(1, 2, 3));
        assertThat(rgb(colors.background().toColor())).isEqualTo(new Rgb(4, 5, 6));
        assertThat(rgb(colors.cursor().toColor())).isEqualTo(new Rgb(7, 8, 9));
        assertThat(rgb(colors.selection().getBackground().toColor())).isEqualTo(new Rgb(10, 11, 12));
    }
}
