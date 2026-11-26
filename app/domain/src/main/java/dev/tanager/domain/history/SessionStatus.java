package dev.tanager.domain.history;

/** 트리에 매단 세션의 상태. 끝난 세션도 창이 남아 있는 동안 트리에 보인다. */
public sealed interface SessionStatus {

    /** 셸이 돌고 있다. */
    record Running() implements SessionStatus {}

    /** 셸이 끝났다. */
    record Exited(int exitCode) implements SessionStatus {}
}
