package tanager.ui.layout;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.swing.JComponent;
import javax.swing.JPanel;
import tanager.domain.layout.LayoutGeometry;
import tanager.domain.layout.LayoutNode;
import tanager.domain.layout.Rect;
import tanager.domain.shared.PaneId;

/**
 * 레이아웃 트리를 화면에 놓는다(#89). 창 컴포넌트를 모두 한 컨테이너에 두고 위치만 {@link LayoutGeometry#rects} 로 정한다 — 분할할 때 터미널
 * 위젯을 다른 부모로 옮기지 않는다. 경계선은 간격만 두고 드래그는 아직 없다. EDT 에서만 만진다.
 */
public final class LayoutView {
    private final JPanel panel = new JPanel(new TreeLayout());
    private final int gap;
    private LayoutNode tree;
    private Map<PaneId, JComponent> panes = Map.of();

    public LayoutView(LayoutNode tree, int gap) {
        this.tree = Objects.requireNonNull(tree, "tree");
        this.gap = gap;
        panel.setOpaque(false);
    }

    /** 놓을 컴포넌트. */
    public JComponent component() {
        return panel;
    }

    /**
     * {@code tree} 대로 {@code components} 를 놓는다. 빠진 컴포넌트는 떼고 새 컴포넌트는 붙인다. 크기를 곧바로 정한다 — 새 창의 셸은 이 크기로
     * 띄운다.
     */
    public void show(LayoutNode next, Map<PaneId, JComponent> components) {
        Objects.requireNonNull(next, "next");
        for (Map.Entry<PaneId, JComponent> old : panes.entrySet()) {
            if (components.get(old.getKey()) != old.getValue()) {
                panel.remove(old.getValue());
            }
        }
        for (Map.Entry<PaneId, JComponent> entry : components.entrySet()) {
            if (entry.getValue().getParent() != panel) {
                panel.add(entry.getValue());
            }
        }
        this.tree = next;
        this.panes = new LinkedHashMap<>(components);
        panel.revalidate();
        panel.validate();
        panel.repaint();
    }

    private final class TreeLayout implements LayoutManager {
        @Override
        public void addLayoutComponent(String name, Component comp) {}

        @Override
        public void removeLayoutComponent(Component comp) {}

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            return new Dimension(0, 0);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return new Dimension(0, 0);
        }

        @Override
        public void layoutContainer(Container parent) {
            Insets in = parent.getInsets();
            Rect bounds = new Rect(
                    in.left,
                    in.top,
                    Math.max(0, parent.getWidth() - in.left - in.right),
                    Math.max(0, parent.getHeight() - in.top - in.bottom));
            Map<PaneId, Rect> rects = LayoutGeometry.rects(tree, bounds, gap);
            rects.forEach((pane, r) -> {
                JComponent component = panes.get(pane);
                if (component != null) {
                    component.setBounds(r.x(), r.y(), r.width(), r.height());
                }
            });
        }
    }
}
