package tanager.ui.terminal;

import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.emulator.ColorPalette;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTypeAheadSettings;
import com.jediterm.terminal.ui.TerminalActionPresentation;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import javax.swing.KeyStroke;
import tanager.application.port.out.TerminalConfig;
import tanager.ui.font.FontChoice;
import tanager.ui.font.FontFallback;
import tanager.ui.font.TerminalFontSet;
import tanager.ui.font.TerminalFonts;
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
 *
 * <p>모든 창이 이 한 벌을 같이 쓴다. 글꼴 · 크기 · 줄 높이 · 스크롤백(FR-CFG-02)과 테마 색(FR-UI-05)은 실행 중에 바뀌고, 바뀌면 붙은
 * 위젯들({@link Listener})에 알린다(#185). JediTerm 은 색 · 글꼴을 그릴 때마다 이 객체에서 읽는다. 바꾸는 쪽은 EDT, 읽는 쪽은 EDT 와
 * 에뮬레이터 스레드라 값은 volatile 이다.
 */
public final class TerminalSettings extends DefaultSettingsProvider {
    static final float LINE_SPACING = (float) TerminalConfig.defaults().lineHeight();

    /** 위젯에 알리는 변경. */
    public enum Change {
        /** 테마 색 — 다시 그린다. */
        COLORS,
        /** 글꼴 · 크기 · 줄 높이 — 칸 크기를 다시 재고 달라졌으면 셸에 resize 를 보낸다. */
        FONT,
        /** 스크롤백 줄 수 — 넘치는 오래된 줄을 버린다. */
        SCROLLBACK
    }

    /** 설정 변경을 받는 쪽(위젯). EDT 에서 불린다. */
    @FunctionalInterface
    public interface Listener {
        void changed(Change change);
    }

    private final TerminalFontSet bundled;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile TerminalFontSet fonts;
    private volatile JediTermColors colors;
    private volatile TerminalConfig config = TerminalConfig.defaults();

    /** @param bundled 번들 글꼴(크기 1). 설정한 글꼴을 쓸 수 없을 때도 이것을 쓴다 */
    public TerminalSettings(TerminalFontSet bundled, JediTermColors colors) {
        this.bundled = Objects.requireNonNull(bundled, "bundled");
        this.fonts = bundled.sized(config.size());
        this.colors = Objects.requireNonNull(colors, "colors");
    }

    /**
     * 설정의 글꼴 · 크기 · 줄 높이 · 스크롤백을 쓴다. 지난번과 같으면 아무것도 하지 않는다. 글꼴을 쓸 수 없으면 번들 글꼴을 그 크기로 쓰고
     * 경고를 돌려준다(FR-UI-06). EDT 에서 부른다.
     */
    public Optional<FontFallback> apply(TerminalConfig next) {
        return apply(next, name -> TerminalFonts.choose(name, bundled));
    }

    Optional<FontFallback> apply(TerminalConfig next, Function<String, FontChoice> choose) {
        Objects.requireNonNull(next, "next");
        TerminalConfig before = config;
        if (next.equals(before)) {
            return Optional.empty();
        }
        FontChoice choice = choose.apply(next.font());
        boolean fontChanged = !next.font().equals(before.font())
                || next.size() != before.size()
                || next.lineHeight() != before.lineHeight();
        fonts = choice.fonts().sized(next.size());
        config = next;
        if (fontChanged) {
            notifyAll(Change.FONT);
        }
        if (next.scrollback() != before.scrollback()) {
            notifyAll(Change.SCROLLBACK);
        }
        return choice.fallback();
    }

    /** 테마 색을 바꾸고 위젯들을 다시 그린다(FR-UI-05). EDT 에서 부른다. */
    public void colors(JediTermColors next) {
        colors = Objects.requireNonNull(next, "next");
        notifyAll(Change.COLORS);
    }

    void addListener(Listener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** 붙은 위젯 수(테스트용). */
    int listenerCount() {
        return listeners.size();
    }

    private void notifyAll(Change change) {
        for (Listener listener : listeners) {
            listener.changed(change);
        }
    }

    /** 위젯이 그릴 때 쓰는 지금 글꼴 한 벌(설정 크기). */
    TerminalFontSet fonts() {
        return fonts;
    }

    /** 화면 밖으로 남길 줄 수. 넘치는 줄은 위젯이 버린다. */
    int scrollback() {
        return config.scrollback();
    }

    /**
     * {@code columns} 열 × {@code rows} 행 칸이 차지하는 픽셀(#150 — 창 최소 크기). JediTerm 과 같은 기준이다: 칸 폭은 'W' 의 폭, 칸 높이는
     * 글꼴 높이 × 줄 간격(올림).
     */
    public Dimension cellArea(Component component, int columns, int rows) {
        FontMetrics metrics = component.getFontMetrics(getTerminalFont());
        int rowHeight = (int) Math.ceil(metrics.getHeight() * getLineSpacing());
        return new Dimension(columns * metrics.charWidth('W'), rows * rowHeight);
    }

    @Override
    public Font getTerminalFont() {
        return fonts.regular();
    }

    @Override
    public float getTerminalFontSize() {
        return config.size();
    }

    @Override
    public float getLineSpacing() {
        return (float) config.lineHeight();
    }

    // JediTerm 버퍼는 만들 때 상한이 정해지고 바꿀 수 없다 — 상한은 설정 최댓값으로 두고 줄 수는 위젯이 지금 설정으로 자른다(#185)
    @Override
    public int getBufferMaxLinesCount() {
        return TerminalConfig.MAX_SCROLLBACK;
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
     * 위젯의 스타일 상태. 읽히지 않는 ANSI 글자색 · 배경색 조합을 보정한다(#113). 기본 스타일은 기본 전경 · 배경이고, 둘 다 지금 테마를
     * 따른다(#185). 위젯마다 새로 만든다.
     */
    public StyleState styleState() {
        return JediTermColors.styleState(() -> colors);
    }

    // JediTermWidget 이 StyleState 의 기본 스타일을 아직 이 (사용 중단된) 메서드로 만든다 — 3.76 기준
    @Override
    @Deprecated
    public TextStyle getDefaultStyle() {
        return JediTermColors.defaultStyle(() -> colors);
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
