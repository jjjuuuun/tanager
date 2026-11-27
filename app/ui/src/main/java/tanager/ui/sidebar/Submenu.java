package tanager.ui.sidebar;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 하위 메뉴(#91). 이유가 있거나 항목이 없으면 비활성이고, 이유는 툴팁으로 보인다.
 *
 * @param reason 비활성 이유(예: 배포판을 찾을 수 없다)
 */
record Submenu(String label, List<MenuItem> items, Optional<String> reason) implements MenuEntry {
    Submenu {
        Objects.requireNonNull(label, "label");
        items = List.copyOf(items);
        Objects.requireNonNull(reason, "reason");
    }

    boolean enabled() {
        return reason.isEmpty() && !items.isEmpty();
    }
}
