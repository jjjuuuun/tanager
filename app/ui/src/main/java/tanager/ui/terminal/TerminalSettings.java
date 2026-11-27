package tanager.ui.terminal;

import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.emulator.ColorPalette;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTypeAheadSettings;
import com.jediterm.terminal.ui.TerminalActionPresentation;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;
import java.awt.Font;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Objects;
import javax.swing.KeyStroke;
import tanager.ui.font.TerminalFontSet;
import tanager.ui.i18n.Msg;
import tanager.ui.theme.JediTermColors;

/**
 * Tanager 터미널 설정(ADR-0011): 번들 D2Coding 14pt · 줄 간격 1.2 · 그레이스케일 AA · 테마 팔레트. type-ahead 는 ConPTY 의 다시
 * 그리기와 다투어 끄고, 종 소리도 끈다.
 *
 * <p>단축키는 Windows Terminal 기본값을 따르고 셸이 쓰는 키(Ctrl+C · Ctrl+V · Ctrl+L · Ctrl+F · Ctrl+↑/↓ · Shift+PgUp/PgDn)는
 * 가로채지 않는다: 복사 Ctrl+Shift+C · Ctrl+Insert, 붙여넣기 Ctrl+Shift+V · Shift+Insert, 찾기 Ctrl+Shift+F, 한 쪽 스크롤
 * Ctrl+Shift+PgUp/PgDn, 한 줄 스크롤 Ctrl+Shift+↑/↓, 화면 지우기 · 모두 선택은 단축키 없음(메뉴로만). macOS 단축키는 macOS
 * 지원 때 정한다.
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
                Msg.TERMINAL_COPY.text(),
                List.of(
                        ctrlShift(KeyEvent.VK_C),
                        KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, InputEvent.CTRL_DOWN_MASK)));
    }

    @Override
    public TerminalActionPresentation getPasteActionPresentation() {
        return new TerminalActionPresentation(
                Msg.TERMINAL_PASTE.text(),
                List.of(
                        ctrlShift(KeyEvent.VK_V),
                        KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, InputEvent.SHIFT_DOWN_MASK)));
    }

    @Override
    public TerminalActionPresentation getFindActionPresentation() {
        return new TerminalActionPresentation(Msg.TERMINAL_FIND.text(), ctrlShift(KeyEvent.VK_F));
    }

    @Override
    public TerminalActionPresentation getClearBufferActionPresentation() {
        return new TerminalActionPresentation(Msg.TERMINAL_CLEAR.text(), List.of());
    }

    @Override
    public TerminalActionPresentation getPageUpActionPresentation() {
        return new TerminalActionPresentation(Msg.TERMINAL_PAGE_UP.text(), ctrlShift(KeyEvent.VK_PAGE_UP));
    }

    @Override
    public TerminalActionPresentation getPageDownActionPresentation() {
        return new TerminalActionPresentation(Msg.TERMINAL_PAGE_DOWN.text(), ctrlShift(KeyEvent.VK_PAGE_DOWN));
    }

    @Override
    public TerminalActionPresentation getLineUpActionPresentation() {
        return new TerminalActionPresentation(Msg.TERMINAL_LINE_UP.text(), ctrlShift(KeyEvent.VK_UP));
    }

    @Override
    public TerminalActionPresentation getLineDownActionPresentation() {
        return new TerminalActionPresentation(Msg.TERMINAL_LINE_DOWN.text(), ctrlShift(KeyEvent.VK_DOWN));
    }

    @Override
    public TerminalActionPresentation getSelectAllActionPresentation() {
        return new TerminalActionPresentation(Msg.TERMINAL_SELECT_ALL.text(), List.of());
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

    /**
     * 위젯의 스타일 상태. 읽히지 않는 ANSI 글자색 · 배경색 조합을 보정한다(#113). 기본 스타일은 기본 전경 · 배경이다. 위젯마다 새로
     * 만든다.
     */
    public StyleState styleState() {
        StyleState state = colors.styleState();
        state.setDefaultStyle(new TextStyle(colors.foreground(), colors.background()));
        return state;
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

    @Override
    public TextStyle getFoundPatternColor() {
        return colors.foundPattern();
    }

    @Override
    public TextStyle getHyperlinkColor() {
        return colors.hyperlink();
    }

    /** 반전 대신 테마의 선택색으로 칠한다. */
    @Override
    public boolean useInverseSelectionColor() {
        return false;
    }

    private static KeyStroke ctrlShift(int keyCode) {
        return KeyStroke.getKeyStroke(keyCode, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
    }
}
