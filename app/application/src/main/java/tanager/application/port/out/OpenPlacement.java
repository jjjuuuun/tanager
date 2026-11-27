package tanager.application.port.out;

/** 트리에서 연 창을 어디에 둘지(config {@code openPlacement}, SDD §9.3). */
public enum OpenPlacement {
    /** 포커스 창의 오른쪽. */
    RIGHT,
    /** 포커스 창의 아래. */
    DOWN,
    /** 맨 뒤의 새 탭(#177). */
    NEW_TAB
}
