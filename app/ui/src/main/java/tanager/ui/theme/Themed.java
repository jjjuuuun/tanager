package tanager.ui.theme;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics;
import java.awt.Insets;
import java.util.Objects;
import javax.swing.JComponent;
import javax.swing.border.AbstractBorder;
import javax.swing.border.Border;

/**
 * 만들 때 칠하는 컴포넌트 색을 토큰으로 기억해 두었다가 테마를 바꾸면 다시 칠한다(#185). 토큰은 컴포넌트의 client property 에 둔다 — 따로
 * 목록을 들지 않아 떼어 낸 컴포넌트가 남지 않는다.
 *
 * <p>LaF 를 바꾸면 {@code updateUI} 가 글자색 · 배경을 새 LaF 의 기본값으로 덮으므로 {@link #recolor} 는 그 다음에 부른다. 그릴 때마다
 * 색을 읽는 곳({@code paintComponent} 의 {@link ThemeColors#get})은 다시 그리기만 하면 된다. EDT 에서만 쓴다.
 */
public final class Themed {
    private static final String FOREGROUND = "Tanager.themed.foreground";
    private static final String BACKGROUND = "Tanager.themed.background";

    private Themed() {}

    /** {@code token} 색으로 글자를 칠하고 기억한다. 같은 컴포넌트에 다시 주면 마지막 토큰을 쓴다. */
    public static <C extends JComponent> C foreground(C component, ColorToken token) {
        Objects.requireNonNull(token, "token");
        component.putClientProperty(FOREGROUND, token);
        component.setForeground(ThemeColors.get(token));
        return component;
    }

    /** {@code token} 색으로 배경을 칠하고 기억한다. */
    public static <C extends JComponent> C background(C component, ColorToken token) {
        Objects.requireNonNull(token, "token");
        component.putClientProperty(BACKGROUND, token);
        component.setBackground(ThemeColors.get(token));
        return component;
    }

    /** 변마다 두께가 다른 선 테두리. 그릴 때 지금 테마의 {@code token} 색을 읽는다. */
    public static Border line(int top, int left, int bottom, int right, ColorToken token) {
        return new TokenLine(new Insets(top, left, bottom, right), Objects.requireNonNull(token, "token"));
    }

    /** {@code root} 와 그 아래 컴포넌트 중 토큰을 기억한 것을 지금 테마 색으로 다시 칠한다. */
    public static void recolor(Component root) {
        if (root instanceof JComponent component) {
            if (component.getClientProperty(FOREGROUND) instanceof ColorToken token) {
                component.setForeground(ThemeColors.get(token));
            }
            if (component.getClientProperty(BACKGROUND) instanceof ColorToken token) {
                component.setBackground(ThemeColors.get(token));
            }
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                recolor(child);
            }
        }
    }

    private static final class TokenLine extends AbstractBorder {
        private final Insets widths;
        private final ColorToken token;

        TokenLine(Insets widths, ColorToken token) {
            this.widths = widths;
            this.token = token;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            Graphics paint = g.create();
            try {
                paint.setColor(ThemeColors.get(token));
                paint.fillRect(x, y, width, widths.top);
                paint.fillRect(x, y + height - widths.bottom, width, widths.bottom);
                paint.fillRect(x, y, widths.left, height);
                paint.fillRect(x + width - widths.right, y, widths.right, height);
            } finally {
                paint.dispose();
            }
        }

        @Override
        public Insets getBorderInsets(Component c, Insets insets) {
            insets.set(widths.top, widths.left, widths.bottom, widths.right);
            return insets;
        }

        @Override
        public boolean isBorderOpaque() {
            return true;
        }
    }
}
