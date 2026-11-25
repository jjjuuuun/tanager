package dev.tanager.ui.terminal;

import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.emulator.ColorPalette;
import com.jediterm.terminal.model.TerminalTypeAheadSettings;
import com.jediterm.terminal.ui.TerminalActionPresentation;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;
import dev.tanager.ui.font.TerminalFontSet;
import dev.tanager.ui.theme.JediTermColors;
import java.awt.Font;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Objects;
import javax.swing.KeyStroke;

/**
 * Tanager 터미널 설정(ADR-0011): 번들 D2Coding 14pt · 줄 간격 1.2 · 그레이스케일 AA · 테마 팔레트. type-ahead 는 ConPTY 의 다시
 * 그리기와 다투어 끄고, 종 소리도 끈다. Ctrl+C · Ctrl+V 는 셸로 보내고 복사 · 붙여넣기는 Ctrl+Shift+C/V 와 Ctrl+Insert ·
 * Shift+Insert 로 한다(Windows 터미널 관례 — macOS 단축키는 macOS 지원 때 정한다).
 */
public final class TerminalSettings extends DefaultSettingsProvider {
    static final float FONT_SIZE = 14f;
    static final float LINE_SPACING = 1.2f;

    private final TerminalFontSet fonts;
    private final JediTermColors colors;

    public TerminalSettings(TerminalFontSet fonts, JediTermColors colors) {
        this.fonts = Objects.requireNonNull(fonts, "fonts").sized(FONT_SIZE);
        this.colors = Objects.requireNonNull(colors, "colors");
    }

    /** 위젯이 굵은 글자를 그릴 때 쓰는 14pt 글꼴 한 벌. */
    TerminalFontSet fonts() {
        return fonts;
    }

    @Override
    public Font getTerminalFont() {
        return fonts.regular();
    }

    @Override
    public float getTerminalFontSize() {
        return FONT_SIZE;
    }

    @Override
    public float getLineSpacing() {
        return LINE_SPACING;
    }

    @Override
    public boolean useAntialiasing() {
        return true;
    }

    @Override
    public boolean audibleBell() {
        return false;
    }

    @Override
    public TerminalTypeAheadSettings getTypeAheadSettings() {
        TerminalTypeAheadSettings defaults = TerminalTypeAheadSettings.DEFAULT;
        return new TerminalTypeAheadSettings(false, defaults.getLatencyThreshold(), defaults.getTypeAheadStyle());
    }

    @Override
    public TerminalActionPresentation getCopyActionPresentation() {
        return new TerminalActionPresentation(
                "복사",
                List.of(
                        KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
                        KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, InputEvent.CTRL_DOWN_MASK)));
    }

    @Override
    public TerminalActionPresentation getPasteActionPresentation() {
        return new TerminalActionPresentation(
                "붙여넣기",
                List.of(
                        KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
                        KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, InputEvent.SHIFT_DOWN_MASK)));
    }

    @Override
    public ColorPalette getTerminalColorPalette() {
        return colors.palette();
    }

    @Override
    public TerminalColor getDefaultForeground() {
        return colors.foreground();
    }

    @Override
    public TerminalColor getDefaultBackground() {
        return colors.background();
    }

    // JediTermWidget 이 StyleState 의 기본 스타일을 아직 이 (사용 중단된) 메서드로 만든다 — 3.76 기준
    @Override
    @Deprecated
    public TextStyle getDefaultStyle() {
        return new TextStyle(colors.foreground(), colors.background());
    }

    @Override
    public TextStyle getSelectionColor() {
        return colors.selection();
    }

    /** 반전 대신 테마의 선택색으로 칠한다. */
    @Override
    public boolean useInverseSelectionColor() {
        return false;
    }
}
