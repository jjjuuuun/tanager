package tanager.ui.terminal;

import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.TerminalPanel;
import com.jediterm.terminal.ui.settings.SettingsProvider;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.event.KeyEvent;
import java.util.Objects;
import java.util.Optional;
import javax.swing.JScrollBar;
import tanager.application.port.out.TerminalSize;
import tanager.ui.measure.PaneMeter;

/**
 * 세션 하나를 보여 주는 JediTerm 위젯. 세션마다 새로 만든다 — {@code start()} 를 다시 부르면 키 리스너가 두 번 붙고
 * {@code close()} 는 executor 를 끝내 다시 쓸 수 없다(ADR-0011).
 *
 * <p>설정({@link TerminalSettings})이 바뀌면 다시 만들지 않고 따라간다(#185): 색은 다시 그리고, 글꼴은 칸 크기를 다시 재 달라졌으면 셸에
 * resize 를 한 번 보내고, 스크롤백은 넘치는 오래된 줄을 버린다. {@link #close()} 하면 설정에서 떨어진다.
 *
 * <p>측정 모드(#222)이면 키({@code KEY_TYPED}) · 버퍼 변경 · 그림 끝을 {@link PaneMeter} 에 알린다. 꺼져 있으면 버퍼 고리를 달지 않는다.
 */
public final class TanagerTermWidget extends JediTermWidget {
    private final TerminalSettings settings;
    private final TerminalSettings.Listener listener = this::settingsChanged;

    public TanagerTermWidget(TerminalSettings settings) {
        this(settings, PaneMeter.OFF);
    }

    /** @param meter 이 창의 입력 반응 측정(#222). 측정 모드가 아니면 {@link PaneMeter#OFF} */
    public TanagerTermWidget(TerminalSettings settings, PaneMeter meter) {
        super(settings);
        this.settings = settings;
        // JediTerm 버퍼의 상한은 설정 최댓값이다 — 줄이 늘 때마다 지금 스크롤백으로 자른다
        getTerminalTextBuffer().addModelListener(this::trimHistory);
        settings.addListener(listener);
        if (Objects.requireNonNull(meter, "meter") != PaneMeter.OFF) {
            getTerminalTextBuffer().addModelListener(meter::output);
            ((BundledBoldPanel) getTerminalPanel()).meter = meter;
        }
    }

    @Override
    public void close() {
        settings.removeListener(listener);
        super.close();
    }

    private void settingsChanged(TerminalSettings.Change change) {
        switch (change) {
            case COLORS -> {}
            case FONT -> ((BundledBoldPanel) getTerminalPanel()).refit();
            case SCROLLBACK -> trimHistory();
        }
        getTerminalPanel().repaint();
        repaint();
    }

    // 버퍼 잠금은 재진입 가능하다 — 에뮬레이터가 잠근 채 알려도, EDT 에서 불러도 같다
    private void trimHistory() {
        TerminalTextBuffer buffer = getTerminalTextBuffer();
        int limit = settings.scrollback();
        if (buffer.getHistoryLinesCount() <= limit) {
            return;
        }
        buffer.modify(() -> {
            while (buffer.getHistoryLinesCount() > limit) {
                buffer.getHistoryLinesStorage().removeFromTop();
            }
        });
    }

    // 상위 생성자 안에서 불린다(mySettingsProvider 는 이미 있다) — 읽히지 않는 ANSI 조합을 보정하는 스타일 상태(#113)
    @Override
    protected StyleState createDefaultStyle() {
        return ((TerminalSettings) mySettingsProvider).styleState();
    }

    // 상위 생성자 안에서 불린다(이 클래스의 필드는 아직 없다) — 글꼴은 넘어온 설정에서 꺼낸다
    @Override
    protected TerminalPanel createTerminalPanel(
            SettingsProvider settingsProvider, StyleState styleState, TerminalTextBuffer terminalTextBuffer) {
        return new BundledBoldPanel((TerminalSettings) settingsProvider, terminalTextBuffer, styleState);
    }

    /** 지금 컴포넌트 크기에 들어가는 열 · 행. 아직 배치되지 않아 크기가 없으면 비어 있다. */
    public Optional<TerminalSize> currentSize() {
        return Optional.ofNullable(getTerminalPanel().getTerminalSizeFromComponent())
                .map(size -> TerminalSizes.of(size.getColumns(), size.getRows()));
    }

    /** JediTerm 의 찾기 결과 표시용 스크롤바 대신 테마(FlatLaf)가 그리는 기본 스크롤바. */
    @Override
    protected JScrollBar createScrollBar() {
        return new JScrollBar();
    }

    /**
     * 굵은 글자를 합성 굵게({@code deriveFont(BOLD)}) 대신 설정의 굵은 글꼴(번들 Bold)로 그린다. 글꼴이 바뀌면 그때 다시 고른다. 테스트가
     * 글꼴 선택을 보려고 패키지 전용.
     */
    static final class BundledBoldPanel extends TerminalPanel {
        private final TerminalSettings settings;
        private Font bold;
        private Font boldItalic;
        // EDT 전용. 위젯 생성자가 상위 생성자 뒤에 넣는다
        private PaneMeter meter = PaneMeter.OFF;

        BundledBoldPanel(TerminalSettings settings, TerminalTextBuffer buffer, StyleState styleState) {
            super(settings, buffer, styleState);
            this.settings = settings;
            pickBold();
        }

        /** 바뀐 글꼴 · 줄 높이로 칸 크기를 다시 잰다. 열 · 행이 달라졌으면 JediTerm 이 셸에 resize 를 한 번 보낸다. */
        void refit() {
            pickBold();
            reinitFontAndResize();
        }

        private void pickBold() {
            bold = settings.fonts().bold();
            boldItalic = bold.deriveFont(bold.getStyle() | Font.ITALIC);
        }

        @Override
        public void processKeyEvent(KeyEvent e) {
            // 셸로 보내기 전에 잰다 — 보내는 데 걸린 시간도 반응 시간이다
            if (e.getID() == KeyEvent.KEY_TYPED) {
                meter.keyTyped();
            }
            super.processKeyEvent(e);
        }

        @Override
        public void paintComponent(Graphics g) {
            super.paintComponent(g);
            meter.painted();
        }

        @Override
        protected Font getFontToDisplay(char[] text, int start, int end, TextStyle style) {
            // 상위 구현이 고른 굵기 · 기울임(선 문자 굵게 예외 포함)을 따르고 굵은 글꼴만 바꾼다
            Font chosen = super.getFontToDisplay(text, start, end, style);
            if (!chosen.isBold()) {
                return chosen;
            }
            return chosen.isItalic() ? boldItalic : bold;
        }
    }
}
