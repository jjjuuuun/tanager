package tanager.ui.sidebar;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.Icon;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import tanager.ui.html.Html;
import tanager.ui.theme.ColorToken;
import tanager.ui.theme.ThemeColors;

/** 사이드바 노드 그리기. 색은 테마 토큰만 쓴다(ArchUnit {@code colorsOnlyFromThemeTokens}). */
final class SidebarRenderer extends DefaultTreeCellRenderer {
    private static final int DOT = 8;

    @Override
    public Component getTreeCellRendererComponent(
            JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
        super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
        if (!(value instanceof DefaultMutableTreeNode node) || !(node.getUserObject() instanceof SidebarItem item)) {
            return this;
        }
        setText(item.secondary()
                .map(alias -> "<html>" + Html.escape(item.label()) + " <font color=\"" + hex(ColorToken.MUTED) + "\">"
                        + Html.escape(alias) + "</font></html>")
                .orElse(item.label()));
        setToolTipText(item.tooltip().orElse(null));
        setForeground(ThemeColors.get(colorOf(item)));
        switch (item.kind()) {
            case SESSION_RUNNING -> setIcon(new Dot(ColorToken.OK));
            case SESSION_EXITED -> setIcon(new Dot(ColorToken.BAD));
            case PINNED_SECTION, PINNED, ENVIRONMENT, FOLDER -> {}
        }
        return this;
    }

    // 검색어에 맞은 노드는 강조색(#85)
    private static ColorToken colorOf(SidebarItem item) {
        if (item.matched()) {
            return ColorToken.ACCENT;
        }
        if (item.hidden()) {
            return ColorToken.MUTED;
        }
        return item.kind() == SidebarItem.Kind.ENVIRONMENT || item.kind() == SidebarItem.Kind.PINNED_SECTION
                ? ColorToken.MUTED
                : ColorToken.TEXT;
    }

    private static String hex(ColorToken token) {
        return String.format("#%06x", ThemeColors.get(token).getRGB() & 0xFFFFFF);
    }

    /** 세션 상태 점(실행 중 = ok, 끝남 = bad). */
    private record Dot(ColorToken token) implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color color = ThemeColors.get(token);
                g2.setColor(color);
                g2.fillOval(x + 2, y + (getIconHeight() - DOT) / 2, DOT, DOT);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return DOT + 4;
        }

        @Override
        public int getIconHeight() {
            return DOT + 4;
        }
    }
}
