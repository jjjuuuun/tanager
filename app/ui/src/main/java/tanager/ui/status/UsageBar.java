package tanager.ui.status;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.Icon;
import tanager.domain.monitor.UsageLevel;
import tanager.ui.theme.ColorToken;
import tanager.ui.theme.ThemeColors;

/**
 * 메모리 항목 앞의 작은 막대(#188). 색은 그릴 때마다 테마 토큰에서 읽는다 — 테마가 바뀌어도 새 색으로 그린다. 바탕 = border, 채움 = accent(경고면
 * warn).
 */
final class UsageBar implements Icon {
    private static final int WIDTH = 28;
    private static final int HEIGHT = 6;

    private final double ratio;
    private final UsageLevel level;

    UsageBar(double ratio, UsageLevel level) {
        this.ratio = Math.clamp(ratio, 0, 1);
        this.level = level;
    }

    double ratio() {
        return ratio;
    }

    /** 채움 색(지금 테마). */
    Color fill() {
        return ThemeColors.get(level == UsageLevel.WARN ? ColorToken.WARN : ColorToken.ACCENT);
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(ThemeColors.get(ColorToken.BORDER));
            g2.fillRoundRect(x, y, WIDTH, HEIGHT, HEIGHT, HEIGHT);
            int filled = (int) Math.round(WIDTH * ratio);
            if (filled > 0) {
                g2.setColor(fill());
                g2.fillRoundRect(x, y, Math.max(filled, HEIGHT), HEIGHT, HEIGHT, HEIGHT);
            }
        } finally {
            g2.dispose();
        }
    }

    @Override
    public int getIconWidth() {
        return WIDTH;
    }

    @Override
    public int getIconHeight() {
        return HEIGHT;
    }
}
