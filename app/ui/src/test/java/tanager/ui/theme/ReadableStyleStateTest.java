package tanager.ui.theme;

import static org.assertj.core.api.Assertions.assertThat;

import com.jediterm.core.Color;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.model.StyleState;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 셸이 고른 ANSI 글자색 · 배경색 조합이 읽히지 않으면 그릴 글자색을 보정한다(#113). 예: WSL 에서 {@code /mnt/c} 의 폴더는 GNU ls
 * 기본 {@code ow=34;42}(파랑 글자 + 초록 배경)로 나오는데 소프트 팔레트에서는 1.28 : 1 이다.
 */
class ReadableStyleStateTest {
    private static final TerminalPalette DARK = SoftTheme.DARK.load().terminal();

    private static StyleState stateFor(TerminalPalette palette) {
        return JediTermColors.from(palette).styleState();
    }

    private static Rgb rgb(Color c) {
        return new Rgb(c.getRed(), c.getGreen(), c.getBlue());
    }

    /** JediTerm 이 그리는 글자색: 색 번호면 팔레트, 아니면 그 색, 없으면 기본 전경. */
    private static Rgb shownForeground(TextStyle style, TerminalPalette palette) {
        TerminalColor fg = style.getForeground();
        if (fg == null) {
            return palette.foreground();
        }
        return fg.isIndexed() ? palette.ansi(fg.getColorIndex()) : rgb(fg.toColor());
    }

    private static Rgb shownBackground(TextStyle style, TerminalPalette palette) {
        TerminalColor bg = style.getBackground();
        if (bg == null) {
            return palette.background();
        }
        return bg.isIndexed() ? palette.ansi(bg.getColorIndex()) : rgb(bg.toColor());
    }

    private static TextStyle indexed(int fg, int bg) {
        return new TextStyle(TerminalColor.index(fg), TerminalColor.index(bg));
    }

    /** GNU ls 기본 LS_COLORS 의 배경 있는 조합(글자, 배경). */
    static Stream<Arguments> lsColorPairs() {
        List<Arguments> cases = new ArrayList<>();
        int[][] pairs = {{4, 2}, {0, 2}, {7, 4}, {7, 1}, {0, 3}, {0, 1}};
        String[] names = {"ow=34;42", "tw=30;42", "st=37;44", "su=37;41", "sg=30;43", "ca=30;41"};
        for (SoftTheme theme : SoftTheme.values()) {
            for (int i = 0; i < pairs.length; i++) {
                cases.add(Arguments.of(theme, names[i], pairs[i][0], pairs[i][1]));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("lsColorPairs")
    @DisplayName("ls 기본 색의 배경 조합은 모두 기준 대비 이상으로 그린다")
    void lsColorPairsAreReadable(SoftTheme theme, String name, int fg, int bg) {
        // given
        TerminalPalette palette = theme.load().terminal();
        StyleState state = stateFor(palette);

        // when
        state.setCurrent(indexed(fg, bg));

        // then
        TextStyle current = state.getCurrent();
        assertThat(Contrast.ratio(shownForeground(current, palette), shownBackground(current, palette)))
                .as("%s %s", theme, name)
                .isGreaterThanOrEqualTo(ReadableForeground.MIN_CONTRAST);
    }

    @Test
    @DisplayName("이미 읽히는 조합은 색 번호를 그대로 둔다")
    void readablePairIsUntouched() {
        // given: 검정 글자 / 초록 배경
        StyleState state = stateFor(DARK);
        TextStyle style = indexed(0, 2);

        // when
        state.setCurrent(style);

        // then
        assertThat(state.getCurrent()).isEqualTo(style);
    }

    @Test
    @DisplayName("기본 글자색(지정 없음)도 색 배경 위에서 읽히지 않으면 보정한다")
    void defaultForegroundOnColoredBackgroundIsAdjusted() {
        // given
        StyleState state = stateFor(DARK);

        // when: 글자색 없이 파랑 배경만 준다(예: 44)
        state.setCurrent(new TextStyle(null, TerminalColor.index(4)));

        // then
        TextStyle current = state.getCurrent();
        assertThat(Contrast.ratio(shownForeground(current, DARK), shownBackground(current, DARK)))
                .isGreaterThanOrEqualTo(ReadableForeground.MIN_CONTRAST);
    }

    @Test
    @DisplayName("truecolor 로 직접 고른 글자색은 보정하지 않는다")
    void trueColorForegroundIsUntouched() {
        // given
        StyleState state = stateFor(DARK);
        TextStyle style = new TextStyle(TerminalColor.rgb(0x7A, 0xA2, 0xF7), TerminalColor.index(2));

        // when
        state.setCurrent(style);

        // then
        assertThat(state.getCurrent()).isEqualTo(style);
    }

    @Test
    @DisplayName("기본 배경 위의 글자는 보정하지 않는다")
    void defaultBackgroundIsUntouched() {
        // given
        StyleState state = stateFor(DARK);
        TextStyle style = new TextStyle(TerminalColor.index(4), null);

        // when
        state.setCurrent(style);

        // then
        assertThat(state.getCurrent()).isEqualTo(style);
    }

    @Test
    @DisplayName("반전 글자는 보정하지 않는다")
    void inverseIsUntouched() {
        // given
        StyleState state = stateFor(DARK);
        TextStyle style = new TextStyle.Builder(indexed(4, 2))
                .setOption(TextStyle.Option.INVERSE, true)
                .build();

        // when
        state.setCurrent(style);

        // then
        assertThat(state.getCurrent()).isEqualTo(style);
    }

    @Test
    @DisplayName("보정한 뒤 배경을 기본으로 돌리면 원래 색 번호로 돌아간다")
    void adjustmentDoesNotStickAfterBackgroundReset() {
        // given: 에뮬레이터는 지금 스타일에서 다음 스타일을 만든다(SGR 49 = 기본 배경)
        StyleState state = stateFor(DARK);
        state.setCurrent(indexed(4, 2));

        // when
        state.setCurrent(
                new TextStyle.Builder(state.getCurrent()).setBackground(null).build());

        // then
        assertThat(state.getCurrent().getForeground()).isEqualTo(TerminalColor.index(4));
    }

    @Test
    @DisplayName("보정한 뒤 배경만 바꿔도 원래 글자색에서 다시 판단한다")
    void readjustsFromOriginalForegroundWhenBackgroundChanges() {
        // given: 파랑 / 초록(보정됨) → 파랑 / 어두운 배경(검정, 읽힘)
        StyleState state = stateFor(DARK);
        state.setCurrent(indexed(4, 2));

        // when
        state.setCurrent(new TextStyle.Builder(state.getCurrent())
                .setBackground(TerminalColor.index(0))
                .build());

        // then
        assertThat(state.getCurrent()).isEqualTo(indexed(4, 0));
    }
}
