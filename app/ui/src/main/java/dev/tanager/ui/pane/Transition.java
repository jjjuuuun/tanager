package dev.tanager.ui.pane;

import java.util.List;
import java.util.Objects;

/** 사건 하나를 처리한 결과: 다음 상태와 실행할 효과(순서대로). */
record Transition(PaneState next, List<PaneEffect> effects) {
    Transition {
        Objects.requireNonNull(next, "next");
        effects = List.copyOf(effects);
    }

    /** 상태도 그대로, 효과도 없다. */
    static Transition stay(PaneState state) {
        return new Transition(state, List.of());
    }
}
