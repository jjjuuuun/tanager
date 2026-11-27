package tanager.ui.pane;

import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Objects;
import javax.swing.Icon;
import tanager.ui.theme.ColorToken;
import tanager.ui.theme.ThemeColors;

/** 창 머리줄 버튼의 인라인 아이콘(#149) — 글꼴에 기대지 않게 직접 그린다. 색은 테마 토큰이다. */
final class HeaderIcon implements Icon {
    private static final int SIZE = 14;
    private static final float STROKE = 1.4f;

    /** 그릴 모양: 오른쪽 분할 ▥ · 아래 분할 ▤ · 줌 □ · 닫기 ×. */
    enum Shape {
        SPLIT_RIGHT,
        SPLIT_DOWN,
        ZOOM,
        CLOSE
    }

    private final Shape shape;
    private final ColorToken color;

    HeaderIcon(Shape shape, ColorToken color) {
        this.shape = Objects.requireNonNull(shape, "shape");
        this.color = Objects.requireNonNull(color, "color");
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g2.setColor(ThemeColors.get(color));
            g2.setStroke(new BasicStroke(STROKE, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            float inset = 2f;
            float left = x + inset;
            float top = y + inset;
            float span = SIZE - 2 * inset;
            switch (shape) {
                case SPLIT_RIGHT -> {
                    g2.draw(new RoundRectangle2D.Float(left, top, span, span, 3, 3));
                    g2.draw(new Line2D.Float(left + span / 2, top, left + span / 2, top + span));
                }
                case SPLIT_DOWN -> {
                    g2.draw(new RoundRectangle2D.Float(left, top, span, span, 3, 3));
                    g2.draw(new Line2D.Float(left, top + span / 2, left + span, top + span / 2));
                }
                case ZOOM -> g2.draw(new RoundRectangle2D.Float(left, top, span, span, 3, 3));
                case CLOSE -> {
                    float pad = 1.5f;
                    g2.draw(new Line2D.Float(left + pad, top + pad, left + span - pad, top + span - pad));
                    g2.draw(new Line2D.Float(left + span - pad, top + pad, left + pad, top + span - pad));
                }
            }
        } finally {
            g2.dispose();
        }
    }

    @Override
    public int getIconWidth() {
        return SIZE;
    }

    @Override
    public int getIconHeight() {
        return SIZE;
    }
}
