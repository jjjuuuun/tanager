package tanager.ui.sidebar;

/** 메뉴 한 줄: 누르는 항목 또는 하위 메뉴(#91). */
sealed interface MenuEntry permits MenuItem, Submenu {}
