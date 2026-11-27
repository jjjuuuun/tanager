package tanager.ui.sidebar;

import java.util.Objects;

/** 메뉴 한 줄: 동작 · 글자 · 활성 여부. */
record MenuItem(MenuAction action, String label, boolean enabled) {
    MenuItem {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(label, "label");
    }
}
