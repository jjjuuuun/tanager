package tanager.ui.theme;

import com.jediterm.core.Color;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.emulator.ColorPalette;
import com.jediterm.terminal.model.StyleState;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.UIManager;

/**
 * 테마의 터미널 팔레트를 JediTerm 색으로 옮긴다. JediTerm 색은 이 클래스에서만 만든다(ArchUnit {@code colorsOnlyFromThemeTokens}).
 */
public final class JediTermColors {
    private static final String KEY_PREFIX = "Tanager.term.";
    private static final int BRIGHT_YELLOW = 11;
    private static final int BRIGHT_BLUE = 12;

    private final TerminalPalette source;
    private final ColorPalette palette;
    private final TerminalColor foreground;
    private final TerminalColor background;
    private final TerminalColor cursor;
    private final TextStyle selection;
    private final TextStyle foundPattern;
    private final TextStyle hyperlink;

    private JediTermColors(TerminalPalette source) {
        this.source = source;
        this.palette = new AnsiPalette(source.ansi());
        this.foreground = terminalColor(source.foreground());
        this.background = terminalColor(source.background());
        this.cursor = terminalColor(source.cursor());
        this.selection = new TextStyle(foreground, terminalColor(source.selection()));
        this.foundPattern = new TextStyle(background, terminalColor(source.ansi(BRIGHT_YELLOW)));
        this.hyperlink = new TextStyle(terminalColor(source.ansi(BRIGHT_BLUE)), null);
    }

    /** 설치된 소프트 테마의 {@code Tanager.term.*} 값. 테마가 설치되지 않았으면 {@link IllegalStateException}(버그). */
    public static JediTermColors current() {
        List<Rgb> ansi = new ArrayList<>();
        for (int i = 0; i < TerminalPalette.ANSI_COLORS; i++) {
            ansi.add(uiColor("ansi." + i));
        }
        return from(new TerminalPalette(
                uiColor("foreground"), uiColor("background"), uiColor("cursor"), uiColor("selection"), ansi));
    }

    /** 테마 파일의 터미널 팔레트(UIManager 없이). 실행 중 테마 전환(#185)이 새 테마의 색을 미리 만든다. */
    public static JediTermColors of(SoftTheme theme) {
        return from(theme.load().terminal());
    }

    static JediTermColors from(TerminalPalette palette) {
        return new JediTermColors(palette);
    }

    /** ANSI 16색(전경 · 배경 같은 값). 16 이상은 JediTerm 이 xterm 256색 규칙으로 계산한다. */
    public ColorPalette palette() {
        return palette;
    }

    public TerminalColor foreground() {
        return foreground;
    }

    public TerminalColor background() {
        return background;
    }

    /** JediTerm 3.76 은 커서 색 설정이 없어(반전으로 그린다) 아직 쓰지 않는다. */
    public TerminalColor cursor() {
        return cursor;
    }

    /**
     * 위젯마다 새로 만드는 스타일 상태. 셸이 고른 ANSI 글자색 · 배경색이 읽히지 않으면 글자색을 보정한다(#113). 기본 스타일은 부르는
     * 쪽이 {@code setDefaultStyle} 로 준다.
     */
    public StyleState styleState() {
        return new ReadableStyleState(() -> source);
    }

    /**
     * 테마를 따라가는 스타일 상태(#185). 보정 판단과 기본 스타일 모두 {@code colors} 의 지금 값을 쓴다 — 실행 중에 테마를 바꿔도 위젯을
     * 다시 만들지 않는다.
     */
    public static StyleState styleState(Supplier<JediTermColors> colors) {
        StyleState state = new ReadableStyleState(() -> colors.get().source);
        state.setDefaultStyle(defaultStyle(colors));
        return state;
    }

    /**
     * 그릴 때마다 {@code colors} 의 지금 기본 전경 · 배경을 읽는 기본 스타일. 리셋(SGR 0) 뒤 쓴 글자는 이 스타일을 버퍼에 그대로 담으므로,
     * 고정 RGB 를 담으면 테마를 바꾼 뒤에도 옛 테마 색으로 남는다(#185).
     */
    public static TextStyle defaultStyle(Supplier<JediTermColors> colors) {
        return new TextStyle(
                new TerminalColor(() -> colors.get().foreground().toColor()),
                new TerminalColor(() -> colors.get().background().toColor()));
    }

    /** 선택 영역: 기본 전경 위에 선택 배경. */
    public TextStyle selection() {
        return selection;
    }

    /** 찾기 결과 강조: 배경색 글자 위에 밝은 노랑(ansi 11) 바탕. */
    public TextStyle foundPattern() {
        return foundPattern;
    }

    /** 링크: 밝은 파랑(ansi 12) 글자, 바탕은 그대로. */
    public TextStyle hyperlink() {
        return hyperlink;
    }

    private static Rgb uiColor(String name) {
        java.awt.Color color = UIManager.getColor(KEY_PREFIX + name);
        if (color == null) {
            throw new IllegalStateException("터미널 색 없음: " + KEY_PREFIX + name + " — 소프트 테마가 설치되지 않았다");
        }
        return new Rgb(color.getRed(), color.getGreen(), color.getBlue());
    }

    private static TerminalColor terminalColor(Rgb rgb) {
        return TerminalColor.rgb(rgb.red(), rgb.green(), rgb.blue());
    }

    private static final class AnsiPalette extends ColorPalette {
        private final List<Color> colors;

        AnsiPalette(List<Rgb> ansi) {
            List<Color> converted = new ArrayList<>();
            for (Rgb rgb : ansi) {
                converted.add(new Color(rgb.red(), rgb.green(), rgb.blue()));
            }
            this.colors = List.copyOf(converted);
        }

        @Override
        protected Color getForegroundByColorIndex(int colorIndex) {
            return colors.get(colorIndex);
        }

        @Override
        protected Color getBackgroundByColorIndex(int colorIndex) {
            return colors.get(colorIndex);
        }
    }
}
