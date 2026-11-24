package dev.tanager.spike;

import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.TerminalPanel;
import com.jediterm.terminal.ui.settings.SettingsProvider;
import java.awt.Font;

/**
 * JediTerm 은 글자마다 대체 글꼴을 고르지 않는다(getFontToDisplay 는 굵게·기울임만 고름).
 * 기본 글꼴에 없는 글자(한글 등)만 대체 글꼴로 그리도록 TerminalPanel 을 바꾼다.
 */
final class FallbackFontWidget extends JediTermWidget {
    private static final Font FALLBACK = new Font("Malgun Gothic", Font.PLAIN, 15);

    FallbackFontWidget(SettingsProvider settings) { super(100, 30, settings); }

    @Override
    protected TerminalPanel createTerminalPanel(SettingsProvider settings, StyleState style, TerminalTextBuffer buffer) {
        return new TerminalPanel(settings, buffer, style) {
            @Override
            protected Font getFontToDisplay(char[] text, int start, int end, TextStyle textStyle) {
                Font f = super.getFontToDisplay(text, start, end, textStyle);
                if (f.canDisplayUpTo(text, start, end) == -1) return f;
                return FALLBACK.deriveFont(f.getStyle(), f.getSize2D());
            }
        };
    }
}
