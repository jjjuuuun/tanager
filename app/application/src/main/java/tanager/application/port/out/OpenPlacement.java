package tanager.application.port.out;

/** 트리에서 연 창을 어디에 둘지(config {@code openPlacement}, SDD §9.3). 새 탭(NEW_TAB)은 탭이 생기는 M5 에서 더한다. */
public enum OpenPlacement {
    /** 포커스 창의 오른쪽. */
    RIGHT,
    /** 포커스 창의 아래. */
    DOWN
}
