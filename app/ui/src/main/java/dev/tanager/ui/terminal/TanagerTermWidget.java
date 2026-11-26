package dev.tanager.ui.terminal;

import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.TerminalPanel;
import com.jediterm.terminal.ui.settings.SettingsProvider;
import dev.tanager.application.port.out.TerminalSize;
import dev.tanager.ui.font.TerminalFontSet;
import java.awt.Font;
import java.util.Optional;
import javax.swing.JScrollBar;

/**
 * 세션 하나를 보여 주는 JediTerm 위젯. 세션마다 새로 만든다 — {@code start()} 를 다시 부르면 키 리스너가 두 번 붙고
 * {@code close()} 는 executor 를 끝내 다시 쓸 수 없다(ADR-0011).
 */
public final class TanagerTermWidget extends JediTermWidget {

    public TanagerTermWidget(TerminalSettings settings) {
        super(settings);
    }

    // 상위 생성자 안에서 불린다(이 클래스의 필드는 아직 없다) — 글꼴은 넘어온 설정에서 꺼낸다
    @Override
    protected TerminalPanel createTerminalPanel(
            SettingsProvider settingsProvider, StyleState styleState, TerminalTextBuffer terminalTextBuffer) {
        TerminalFontSet fonts = ((TerminalSettings) settingsProvider).fonts();
        return new BundledBoldPanel(settingsProvider, terminalTextBuffer, styleState, fonts.bold());
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

    /** 굵은 글자를 합성 굵게({@code deriveFont(BOLD)}) 대신 번들 Bold 로 그린다. 테스트가 글꼴 선택을 보려고 패키지 전용. */
    static final class BundledBoldPanel extends TerminalPanel {
        private final Font bold;
        private final Font boldItalic;

        BundledBoldPanel(
                SettingsProvider settings, TerminalTextBuffer buffer, StyleState styleState, Font bundledBold) {
            super(settings, buffer, styleState);
            this.bold = bundledBold;
            this.boldItalic = bundledBold.deriveFont(bundledBold.getStyle() | Font.ITALIC);
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
