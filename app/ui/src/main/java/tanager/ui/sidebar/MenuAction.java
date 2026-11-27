package tanager.ui.sidebar;

/** 사이드바 메뉴의 동작(#86 · #91 · #92). */
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
    /** 포커스 창 오른쪽에 그 폴더를 연다. 세션이면 그 창 오른쪽에 같은 셸 · 같은 경로로 연다. */
    OPEN_RIGHT,
    /** 포커스 창 아래에 그 폴더를 연다. 세션이면 그 창 아래에 같은 셸 · 같은 경로로 연다. */
    OPEN_DOWN,
    /** 고른 셸로 그 폴더를 연다(설정 자리). */
    OPEN_WITH,
    /** 환경의 기본 셸로 새 창(설정 자리 · 기본 경로). */
    NEW_PANE,
    /** 세션의 창에 포커스(키보드는 터미널로). */
    FOCUS,
    /** 끝난 세션을 같은 경로에서 다시 띄운다(끝난 창의 Enter 와 같다). */
    RESTART,
    /** 세션의 창을 닫는다. 실행 중이어도 묻지 않는다. */
    CLOSE
}
