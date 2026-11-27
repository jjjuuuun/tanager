package tanager.ui.sidebar;

/** 사이드바 메뉴의 동작(#86 · #91). */
enum MenuAction {
    COPY_PATH,
    PIN,
    UNPIN,
    ALIAS,
    HIDE,
    UNHIDE,
    FORGET,
    TOGGLE_HIDDEN,
    EXPAND_ALL,
    COLLAPSE_ALL,
    /** 포커스 창 오른쪽에 그 폴더를 연다. */
    OPEN_RIGHT,
    /** 포커스 창 아래에 그 폴더를 연다. */
    OPEN_DOWN,
    /** 고른 셸로 그 폴더를 연다(설정 자리). */
    OPEN_WITH,
    /** 환경의 기본 셸로 새 창(설정 자리 · 기본 경로). */
    NEW_PANE
}
