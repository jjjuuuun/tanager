package tanager.domain.workspace;

import java.util.Objects;
import java.util.Optional;
import tanager.domain.layout.LayoutError;
import tanager.domain.layout.LayoutNode;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.Result;
import tanager.domain.shared.TabId;

/**
 * 탭 하나: 분할 트리 · 포커스 창 · 줌한 창(SDD §5.4, FR-LAY-05 · 07). 불변식: 포커스 창과 줌한 창은 트리 안에 있다.
 *
 * @param name 사용자가 붙인 이름. 비면 화면은 포커스 창 제목을 쓴다(자동 이름)
 * @param zoomed 화면을 혼자 채우는 창. 비면 줌하지 않은 상태
 */
public record Tab(TabId id, Optional<TabName> name, LayoutNode root, PaneId focused, Optional<PaneId> zoomed) {
    public Tab {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(focused, "focused");
        Objects.requireNonNull(zoomed, "zoomed");
        if (!root.panes().contains(focused)) {
            throw new IllegalArgumentException("포커스 창이 트리에 없다: " + focused);
        }
        if (zoomed.isPresent() && !root.panes().contains(zoomed.get())) {
            throw new IllegalArgumentException("줌한 창이 트리에 없다: " + zoomed.get());
        }
    }

    /** 이름 없고 줌하지 않은 탭. */
    public Tab(TabId id, LayoutNode root, PaneId focused) {
        this(id, Optional.empty(), root, focused, Optional.empty());
    }

    boolean sole() {
        return root.panes().size() == 1;
    }

    boolean contains(PaneId pane) {
        return root.panes().contains(pane);
    }

    // 새 트리 · 포커스. 레이아웃이 바뀌었으니 줌은 푼다(#147)
    Tab withLayout(LayoutNode next, PaneId focus) {
        return new Tab(id, name, next, focus, Optional.empty());
    }

    // 같은 창이면 그대로, 다른 창이면 줌을 푼다(#147)
    Tab withFocus(PaneId pane) {
        return pane.equals(focused) ? this : withLayout(root, pane);
    }

    Tab withZoom(Optional<PaneId> pane) {
        return new Tab(id, name, root, focused, pane);
    }

    Tab withName(Optional<TabName> next) {
        return new Tab(id, next, root, focused, zoomed);
    }

    // pane 을 트리에서 뺀다. 포커스 창이면 남는 형제 쪽의 첫 창에 포커스한다. 유일한 창이면 LastPane
    Result<Tab, LayoutError> without(PaneId pane) {
        Optional<LayoutNode> sibling = siblingOf(root, pane);
        return root.close(pane).map(next -> withLayout(next, focusAfterClosing(pane, sibling)));
    }

    private PaneId focusAfterClosing(PaneId pane, Optional<LayoutNode> sibling) {
        return focused.equals(pane) ? sibling.orElseThrow().panes().getFirst() : focused;
    }

    // pane 을 바로 아래 자식으로 가진 Split 의 다른 자식
    private static Optional<LayoutNode> siblingOf(LayoutNode node, PaneId pane) {
        return switch (node) {
            case LayoutNode.Leaf _ -> Optional.empty();
            case LayoutNode.Split split -> {
                if (split.first() instanceof LayoutNode.Leaf(PaneId p) && p.equals(pane)) {
                    yield Optional.of(split.second());
                }
                if (split.second() instanceof LayoutNode.Leaf(PaneId p) && p.equals(pane)) {
                    yield Optional.of(split.first());
                }
                yield siblingOf(split.first(), pane).or(() -> siblingOf(split.second(), pane));
            }
        };
    }
}
