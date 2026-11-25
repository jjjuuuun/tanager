package dev.tanager.ui.theme;

import com.jediterm.core.Color;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.emulator.ColorPalette;
import java.util.ArrayList;
import java.util.List;
import javax.swing.UIManager;

/**
 * 테마의 터미널 팔레트를 JediTerm 색으로 옮긴다. JediTerm 색은 이 클래스에서만 만든다(ArchUnit {@code colorsOnlyFromThemeTokens}).
 */
public final class JediTermColors {
    private static final String KEY_PREFIX = "Tanager.term.";
    private static final int BRIGHT_YELLOW = 11;
    private static final int BRIGHT_BLUE = 12;

    private final ColorPalette palette;
    private final TerminalColor foreground;
    private final TerminalColor background;
    private final TerminalColor cursor;
    private final TextStyle selection;
    private final TextStyle foundPattern;
    private final TextStyle hyperlink;

    private JediTermColors(TerminalPalette source) {
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
