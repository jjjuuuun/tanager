package tanager.ui.layout;

import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import tanager.domain.layout.Divider;
import tanager.domain.layout.LayoutGeometry;
import tanager.domain.layout.LayoutNode;
import tanager.domain.layout.Orientation;
import tanager.domain.layout.Ratio;
import tanager.domain.layout.Rect;
import tanager.domain.layout.SplitPath;
import tanager.domain.shared.PaneId;
import tanager.ui.theme.ColorToken;
import tanager.ui.theme.ThemeColors;

/**
 * 레이아웃 트리를 화면에 놓는다(#89). 창 컴포넌트를 모두 한 컨테이너에 두고 위치만 {@link LayoutGeometry#rects} 로 정한다 — 분할할 때 터미널
 * 위젯을 다른 부모로 옮기지 않는다. EDT 에서만 만진다.
 *
 * <p>경계선 드래그(#150, FR-LAY-11): 창들 위에 투명한 경계선 층을 하나 얹는다. 층은 경계선 띠 위의 점만 자기 것으로 하므로({@link
 * JComponent#contains}) 나머지 마우스는 그대로 터미널이 받는다. 끄는 동안에는 미리보기 선만 그리고 레이아웃 · 창 크기(= 셸 크기)는 바꾸지
 * 않는다. 놓을 때 {@code onResize} 를 한 번 부른다 — 실제 변경은 유스케이스 → {@code LayoutChanged} → {@link #show} 로 돌아온다. 두 번
 * 누르면 반반이다.
 *
 * <p>줌(#149, FR-LAY-05): 줌한 창만 영역을 혼자 채우고 다른 창은 숨긴다(떼지 않아 셸 · 크기 그대로). 줌한 동안에는 경계선이 보이지 않으므로
 * 잡을 경계선도 없다.
 *
 * <p>전환 효과(FR-UI-04)는 {@code tanager.ui.motion.Animator} 로 붙인다. 줌은 지금 바로 바뀐다 — 넣는다면 {@code show} 에서 줌한 창의
 * 사각형을 원래 칸 ↔ 전체 영역으로 보간하되, 경계선 드래그처럼 도중에는 미리보기만 그리고 셸 크기는 끝에서 한 번만 바꾼다. 드롭 영역
 * 미리보기(#151)도 같은 방식이다.
 */
public final class LayoutView {
    /** 경계선을 잡는 띠 폭(px). 크기 테마 토큰이 없어 상수로 둔다 — 창 사이 간격(6px)과 같아 터미널 글자를 거의 가리지 않으면서 잡기 쉽다. */
    static final int HIT_PX = 6;

    private final JPanel panel = new JPanel(new TreeLayout()) {
        // 경계선 층이 창들 위에 겹친다 — 겹침이 없다고 가정한 그리기 최적화를 끄지 않으면 층의 미리보기가 창 밑에 깔린다
        @Override
        public boolean isOptimizedDrawingEnabled() {
            return false;
        }
    };

    private final DividerLayer layer = new DividerLayer();
    private final int gap;
    private final Supplier<PaneMinimum> minimum;
    private final BiConsumer<SplitPath, Ratio> onResize;
    private LayoutNode tree;
    private Map<PaneId, JComponent> panes = Map.of();
    private Optional<PaneId> zoomed = Optional.empty();
    private Optional<Drag> drag = Optional.empty();

    /**
     * @param minimum 창 하나의 최소 크기. 누를 때마다 다시 읽는다(글꼴이 바뀔 수 있다)
     * @param onResize 경계선을 끌어 놓거나 두 번 눌렀을 때 그 Split 과 새 비율로 한 번 부른다. EDT 에서 불린다
     */
    public LayoutView(LayoutNode tree, int gap, Supplier<PaneMinimum> minimum, BiConsumer<SplitPath, Ratio> onResize) {
        this.tree = Objects.requireNonNull(tree, "tree");
        this.gap = gap;
        this.minimum = Objects.requireNonNull(minimum, "minimum");
        this.onResize = Objects.requireNonNull(onResize, "onResize");
        panel.setOpaque(false);
        // 순서 0 = 맨 위. 마우스도 먼저 받는다
        panel.add(layer, 0);
    }

    /** 놓을 컴포넌트. */
    public JComponent component() {
        return panel;
    }

    /** 창들을 놓는 영역(이 컴포넌트 안쪽 좌표). 방향 포커스 · 교환이 이 영역과 {@link #gap()} 으로 이웃을 찾는다(#148). 경계선 판정도 같은 영역을 쓴다. */
    public Rect area() {
        Insets in = panel.getInsets();
        return new Rect(
                in.left,
                in.top,
                Math.max(0, panel.getWidth() - in.left - in.right),
                Math.max(0, panel.getHeight() - in.top - in.bottom));
    }

    /** 창 사이 간격(px). */
    public int gap() {
        return gap;
    }

    /**
     * {@code tree} 대로 {@code components} 를 놓는다. 빠진 컴포넌트는 떼고 새 컴포넌트는 붙인다. 크기를 곧바로 정한다 — 새 창의 셸은 이 크기로
     * 띄운다. 끄는 중이던 경계선은 버린다 — 옛 트리의 경계선으로 크기를 바꾸지 않게.
     */
    public void show(LayoutNode next, Map<PaneId, JComponent> components) {
        show(next, Optional.empty(), components);
    }

    /**
     * {@link #show(LayoutNode, Map)} 과 같되 {@code zoom} 이 있으면 그 창만 영역을 혼자 채우고 다른 창은 숨긴다(#149, FR-LAY-05). 숨긴 창은
     * 떼지 않아 셸 · 크기가 그대로이고, 줌을 풀면 원래 자리로 돌아온다. 줌한 동안에는 경계선 드래그가 없다.
     */
    public void show(LayoutNode next, Optional<PaneId> zoom, Map<PaneId, JComponent> components) {
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(zoom, "zoom");
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
        // 모르는 창의 줌은 무시한다 — 모든 창을 보인다
        this.zoomed = zoom.filter(components::containsKey);
        for (Map.Entry<PaneId, JComponent> entry : components.entrySet()) {
            entry.getValue().setVisible(zoomed.isEmpty() || zoomed.get().equals(entry.getKey()));
        }
        drag = Optional.empty();
        panel.revalidate();
        panel.validate();
        panel.repaint();
    }

    /** 경계선 층(테스트가 합성 마우스 이벤트를 보낸다). */
    JComponent dividerLayer() {
        return layer;
    }

    /** 끄는 중이면 미리보기 선의 자리. */
    Optional<Rect> preview() {
        return drag.flatMap(Drag::preview);
    }

    private Optional<Divider> dividerAt(int x, int y) {
        if (zoomed.isPresent()) {
            return Optional.empty();
        }
        return DividerDrags.at(LayoutGeometry.dividers(tree, area(), gap, HIT_PX), x, y);
    }

    /** 끄는 중인 경계선 하나. {@code ratio} 는 한 번이라도 끌었을 때만 있다 — 누르고 놓기만 하면 크기를 바꾸지 않는다. */
    private record Drag(Divider divider, DividerDrags.Minimums minimums, Optional<Ratio> ratio) {
        Drag to(int x, int y) {
            return new Drag(
                    divider,
                    minimums,
                    Optional.of(LayoutGeometry.ratioAt(divider, x, y, minimums.first(), minimums.second())));
        }

        Optional<Rect> preview() {
            return ratio.map(r -> DividerDrags.preview(divider, r));
        }
    }

    private static Cursor cursorFor(Orientation orientation) {
        return Cursor.getPredefinedCursor(
                switch (orientation) {
                    case ROW -> Cursor.E_RESIZE_CURSOR;
                    case COLUMN -> Cursor.N_RESIZE_CURSOR;
                });
    }

    /** 창들 위의 투명한 층. 경계선 띠에서만(끄는 중이면 어디서나) 마우스를 받고 미리보기 선을 그린다. */
    private final class DividerLayer extends JComponent {
        DividerLayer() {
            setOpaque(false);
            MouseAdapter mouse = new Mouse();
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        @Override
        public boolean contains(int x, int y) {
            return drag.isPresent() || dividerAt(x, y).isPresent();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Optional<Rect> line = preview();
            if (line.isEmpty()) {
                return;
            }
            Rect r = line.get();
            g.setColor(ThemeColors.get(ColorToken.ACCENT));
            g.fillRect(r.x(), r.y(), r.width(), r.height());
        }

        private void hover(int x, int y) {
            setCursor(dividerAt(x, y).map(d -> cursorFor(d.orientation())).orElse(Cursor.getDefaultCursor()));
        }
    }

    private final class Mouse extends MouseAdapter {
        @Override
        public void mouseMoved(MouseEvent e) {
            layer.hover(e.getX(), e.getY());
        }

        @Override
        public void mousePressed(MouseEvent e) {
            if (!SwingUtilities.isLeftMouseButton(e)) {
                return;
            }
            Optional<Divider> divider = dividerAt(e.getX(), e.getY());
            if (divider.isEmpty()) {
                return;
            }
            if (e.getClickCount() >= 2) {
                drag = Optional.empty();
                onResize.accept(divider.get().path(), Ratio.HALF);
                return;
            }
            Divider d = divider.get();
            drag = Optional.of(new Drag(d, DividerDrags.minimums(tree, d, minimum.get()), Optional.empty()));
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            if (drag.isEmpty()) {
                return;
            }
            drag = drag.map(d -> d.to(e.getX(), e.getY()));
            layer.repaint();
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            Optional<Drag> done = drag;
            drag = Optional.empty();
            layer.repaint();
            layer.hover(e.getX(), e.getY());
            done.ifPresent(d ->
                    d.ratio().ifPresent(ratio -> onResize.accept(d.divider().path(), ratio)));
        }
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
            layer.setBounds(0, 0, parent.getWidth(), parent.getHeight());
            if (zoomed.isPresent()) {
                Rect r = area();
                panes.get(zoomed.get()).setBounds(r.x(), r.y(), r.width(), r.height());
                return;
            }
            Map<PaneId, Rect> rects = LayoutGeometry.rects(tree, area(), gap);
            rects.forEach((pane, r) -> {
                JComponent component = panes.get(pane);
                if (component != null) {
                    component.setBounds(r.x(), r.y(), r.width(), r.height());
                }
            });
        }
    }
}
