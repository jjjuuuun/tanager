package tanager.ui.layout;

import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TooManyListenersException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import tanager.domain.history.WorkspacePath;
import tanager.domain.layout.Divider;
import tanager.domain.layout.DropZone;
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
 *
 * <p>끌어다 놓기(#151, FR-LAY-12 · 13): 창의 머리줄 손잡이({@link #addDragHandle})를 {@value PaneDrags#THRESHOLD_PX}px 넘게 끌면 점 아래
 * 다른 창의 놓을 곳(상 · 하 · 좌 · 우 · 가운데)을 같은 층에 반투명으로 보이고, 놓으면 {@code Drops.movePane} 을 한 번 부른다. Esc 는 {@link
 * #cancelDrag}. 줌한 동안에는 끌 수 없다. 사이드바 폴더는 Swing 끌어다 놓기로 이 컴포넌트에 오고({@link FolderDrops}) 같은 미리보기를 쓴 뒤
 * {@code Drops.openFolder} 를 부른다.
 */
public final class LayoutView {
    /** 경계선을 잡는 띠 폭(px). 크기 테마 토큰이 없어 상수로 둔다 — 창 사이 간격(6px)과 같아 터미널 글자를 거의 가리지 않으면서 잡기 쉽다. */
    static final int HIT_PX = 6;
    /** 놓을 곳 미리보기의 불투명도(0 ~ 255). 밑의 터미널 글자가 비쳐 보일 만큼만 칠한다. */
    private static final int DROP_ALPHA = 56;

    private static final float DROP_STROKE = 2f;

    /**
     * 끌어다 놓기의 결과를 받는 쪽(#151). 둘 다 EDT 에서 놓을 때 한 번 불린다.
     *
     * @param movePane 머리줄을 끌어 다른 창의 놓을 곳에 놓았다 — 끈 창과 놓을 곳
     * @param openFolder 사이드바 폴더를 창의 놓을 곳에 놓았다 — 그 경로와 놓을 곳
     */
    public record Drops(BiConsumer<PaneId, DropZone> movePane, BiConsumer<WorkspacePath, DropZone> openFolder) {
        /** 아무것도 하지 않는다(끌어다 놓기를 쓰지 않는 곳 · 테스트). */
        public static final Drops NONE = new Drops((pane, zone) -> {}, (path, zone) -> {});

        public Drops {
            Objects.requireNonNull(movePane, "movePane");
            Objects.requireNonNull(openFolder, "openFolder");
        }
    }

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
    private final Drops drops;
    private final MouseAdapter handleMouse = new HandleMouse();
    private LayoutNode tree;
    private Map<PaneId, JComponent> panes = Map.of();
    private Optional<PaneId> zoomed = Optional.empty();
    private Optional<Drag> drag = Optional.empty();
    // 머리줄을 누른 · 끄는 창(#151)과 사이드바 폴더를 끄는 중인 놓을 곳
    private Optional<PaneDrags.Grab> grab = Optional.empty();
    private Optional<DropZone> folderZone = Optional.empty();

    /**
     * @param minimum 창 하나의 최소 크기. 누를 때마다 다시 읽는다(글꼴이 바뀔 수 있다)
     * @param onResize 경계선을 끌어 놓거나 두 번 눌렀을 때 그 Split 과 새 비율로 한 번 부른다. EDT 에서 불린다
     */
    public LayoutView(LayoutNode tree, int gap, Supplier<PaneMinimum> minimum, BiConsumer<SplitPath, Ratio> onResize) {
        this(tree, gap, minimum, onResize, Drops.NONE);
    }

    /** @param drops 끌어다 놓기(#151)의 결과를 받는 쪽 */
    public LayoutView(
            LayoutNode tree,
            int gap,
            Supplier<PaneMinimum> minimum,
            BiConsumer<SplitPath, Ratio> onResize,
            Drops drops) {
        this.tree = Objects.requireNonNull(tree, "tree");
        this.gap = gap;
        this.minimum = Objects.requireNonNull(minimum, "minimum");
        this.onResize = Objects.requireNonNull(onResize, "onResize");
        this.drops = Objects.requireNonNull(drops, "drops");
        panel.setOpaque(false);
        // 순서 0 = 맨 위. 마우스도 먼저 받는다
        panel.add(layer, 0);
        acceptFolders();
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
        // 머리줄을 누르면 그 창에 포커스가 가서 같은 창들로 다시 그려진다 — 끄는 창이 남아 있으면 끌기를 잇는다. 놓을 곳은 옛 배치의
        // 것이라 지우고 다음 움직임에서 다시 구한다
        grab = grab.filter(g -> zoomed.isEmpty() && components.containsKey(g.pane()))
                .map(LayoutView::withoutZone);
        folderZone = Optional.empty();
        panel.revalidate();
        panel.validate();
        panel.repaint();
    }

    private static PaneDrags.Grab withoutZone(PaneDrags.Grab grab) {
        return switch (grab) {
            case PaneDrags.Grab.Pressed pressed -> pressed;
            case PaneDrags.Grab.Dragging dragging -> new PaneDrags.Grab.Dragging(dragging.pane(), Optional.empty());
        };
    }

    /**
     * {@code handle} 을 끌어 그 손잡이가 든 창을 옮기게 한다(#151). 창 컴포넌트 안의 머리줄 · 손잡이를 넘긴다 — 어느 창인지는 누를 때 조상으로
     * 찾는다. 손잡이마다 한 번 부른다. 클릭 · 더블클릭은 손잡이의 원래 리스너가 그대로 받는다.
     */
    public void addDragHandle(JComponent handle) {
        handle.addMouseListener(handleMouse);
        handle.addMouseMotionListener(handleMouse);
    }

    /** 머리줄 끌기를 취소한다(Esc, #151). 누르거나 끄는 중이었으면 true — 그때만 Esc 를 가로챈다. */
    public boolean cancelDrag() {
        if (grab.isEmpty()) {
            return false;
        }
        grab = Optional.empty();
        layer.repaint();
        return true;
    }

    /** 머리줄을 문턱 넘게 끄는 중인가(테스트용). */
    boolean draggingPane() {
        return grab.filter(PaneDrags.Grab.Dragging.class::isInstance).isPresent();
    }

    /** 지금 보이는 놓을 곳 미리보기(창 · 폴더 끌기). */
    Optional<Rect> dropPreview() {
        Optional<DropZone> pane = grab.flatMap(PaneDrags::released);
        if (pane.isPresent()) {
            return PaneDrags.highlight(tree, Optional.empty(), area(), gap, pane.get());
        }
        return folderZone.flatMap(zone -> PaneDrags.highlight(tree, zoomed, area(), gap, zone));
    }

    /** 사이드바 폴더를 ({@code x}, {@code y}) 위로 끄는 중(창 영역 좌표). 창 위면 놓을 곳을 보이고 true. */
    boolean hoverFolder(int x, int y) {
        Optional<DropZone> next = PaneDrags.folderZone(tree, zoomed, area(), gap, x, y);
        if (!next.equals(folderZone)) {
            folderZone = next;
            layer.repaint();
        }
        return next.isPresent();
    }

    /** 사이드바 폴더를 ({@code x}, {@code y}) 에 놓았다. 창 위면 {@code Drops.openFolder} 를 한 번 부르고 true. */
    boolean dropFolder(WorkspacePath path, int x, int y) {
        Objects.requireNonNull(path, "path");
        Optional<DropZone> zone = PaneDrags.folderZone(tree, zoomed, area(), gap, x, y);
        endFolderHover();
        zone.ifPresent(z -> drops.openFolder().accept(path, z));
        return zone.isPresent();
    }

    /** 폴더 끌기가 창 영역을 벗어났거나 끝났다 — 미리보기를 지운다. */
    void endFolderHover() {
        if (folderZone.isPresent()) {
            folderZone = Optional.empty();
            layer.repaint();
        }
    }

    // 놓기 대상은 화면이 있을 때만 생긴다(헤드리스에는 없다). TransferHandler 에는 벗어남 알림이 없어 리스너로 미리보기를 지운다
    private void acceptFolders() {
        panel.setTransferHandler(new FolderDrops(this));
        Optional.ofNullable(panel.getDropTarget()).ifPresent(target -> {
            try {
                target.addDropTargetListener(new DropTargetAdapter() {
                    @Override
                    public void dragExit(DropTargetEvent e) {
                        endFolderHover();
                    }

                    @Override
                    public void drop(DropTargetDropEvent e) {
                        endFolderHover();
                    }
                });
            } catch (TooManyListenersException e) {
                throw new IllegalStateException("놓기 대상에 리스너를 달 수 없다", e);
            }
        });
    }

    private Optional<PaneId> paneOf(Component source) {
        for (Map.Entry<PaneId, JComponent> entry : panes.entrySet()) {
            if (SwingUtilities.isDescendingFrom(source, entry.getValue())) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
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
            dropPreview().ifPresent(zone -> paintDrop(g, zone));
            Optional<Rect> line = preview();
            if (line.isEmpty()) {
                return;
            }
            Rect r = line.get();
            g.setColor(ThemeColors.get(ColorToken.ACCENT));
            g.fillRect(r.x(), r.y(), r.width(), r.height());
        }

        // 강조색을 반투명하게 칠하고 테두리를 둘러 놓을 곳을 보인다. 모서리는 창과 같은 반지름
        private void paintDrop(Graphics g, Rect r) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                int arc = UIManager.getInt("Tanager.pane.arc");
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(ThemeColors.translucent(ColorToken.ACCENT, DROP_ALPHA));
                g2.fillRoundRect(r.x(), r.y(), r.width(), r.height(), arc, arc);
                g2.setColor(ThemeColors.get(ColorToken.ACCENT));
                g2.setStroke(new BasicStroke(DROP_STROKE));
                g2.drawRoundRect(
                        r.x() + 1, r.y() + 1, Math.max(0, r.width() - 2), Math.max(0, r.height() - 2), arc, arc);
            } finally {
                g2.dispose();
            }
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

    /**
     * 창 머리줄 손잡이의 끌기(#151). 누르면 창을 기억하고, 문턱을 넘게 끌면 놓을 곳을 따라가며, 놓으면 한 번 옮긴다. 좌표는 손잡이 기준이라 창 영역
     * 좌표로 바꾼다. 끄는 동안 마우스 사건은 누른 손잡이로 계속 온다(AWT 의 끌기 잡기).
     */
    private final class HandleMouse extends MouseAdapter {
        @Override
        public void mousePressed(MouseEvent e) {
            if (!SwingUtilities.isLeftMouseButton(e) || e.isPopupTrigger() || zoomed.isPresent()) {
                return;
            }
            Point at = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), panel);
            grab = paneOf(e.getComponent()).map(pane -> new PaneDrags.Grab.Pressed(pane, at.x, at.y));
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            if (grab.isEmpty()) {
                return;
            }
            Point at = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), panel);
            grab = grab.map(g -> PaneDrags.moved(g, tree, area(), gap, at.x, at.y));
            layer.repaint();
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            if (grab.isEmpty() || !SwingUtilities.isLeftMouseButton(e)) {
                return;
            }
            PaneDrags.Grab done = grab.get();
            grab = Optional.empty();
            layer.repaint();
            PaneDrags.released(done).ifPresent(zone -> drops.movePane().accept(done.pane(), zone));
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
