package tanager.domain.workspace;

import java.util.Objects;
import tanager.domain.layout.LayoutNode;
import tanager.domain.shared.PaneId;

/** 탭 하나: 분할 트리와 포커스 창(SDD §5.4). 불변식: 포커스 창은 트리 안에 있다. 이름 · 줌은 탭이 여럿이 되는 M5 에서 더한다. */
public record Tab(LayoutNode root, PaneId focused) {
    public Tab {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(focused, "focused");
        if (!root.panes().contains(focused)) {
            throw new IllegalArgumentException("포커스 창이 트리에 없다: " + focused);
        }
    }
}
