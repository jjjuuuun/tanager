package tanager.ui.keys;

import java.util.Objects;

/** 메인 창이 터미널보다 먼저 본 키 이벤트 하나의 판정(#148 · #193). */
public sealed interface KeyRoute {

    /** 가로채지 않는다 — 포커스 부품(터미널이면 셸)이 받는다. */
    record Pass() implements KeyRoute {}

    /** 삼킨다 — 아무 데도 가지 않는다. */
    record Swallow() implements KeyRoute {}

    /** 삼키고 {@code action} 을 부른다. */
    record Run(KeyAction action) implements KeyRoute {
        public Run {
            Objects.requireNonNull(action, "action");
        }
    }
}
