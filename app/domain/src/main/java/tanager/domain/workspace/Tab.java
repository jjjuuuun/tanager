package tanager.domain.workspace;

import java.util.Objects;
import java.util.Optional;
import tanager.domain.layout.LayoutNode;
import tanager.domain.shared.PaneId;

/**
 * 탭 하나: 분할 트리 · 포커스 창 · 줌한 창(SDD §5.4, FR-LAY-05). 불변식: 포커스 창과 줌한 창은 트리 안에 있다. 이름은 탭이 여럿이 되는 M5 에서
 * 더한다.
 *
 * @param zoomed 화면을 혼자 채우는 창. 비면 줌하지 않은 상태
 */
public record Tab(LayoutNode root, PaneId focused, Optional<PaneId> zoomed) {
    public Tab {
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

    /** 줌하지 않은 탭. */
    public Tab(LayoutNode root, PaneId focused) {
        this(root, focused, Optional.empty());
    }
}
