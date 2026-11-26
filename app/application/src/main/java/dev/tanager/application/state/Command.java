package dev.tanager.application.state;

/**
 * 상태 하나를 받아 다음 상태 · 이벤트 · 응답을 계산하는 순수 함수(ADR-0012). {@link StateActor} 의 스레드에서만 돈다. 실패는 응답 값으로
 * 돌려주고, 예외는 버그에만 쓴다.
 */
@FunctionalInterface
public interface Command<R> {

    /** {@code current} 위에서 계산한 전이. 상태를 바꾸지 않으면 {@code current} 그대로와 빈 이벤트 목록을 돌려준다. */
    Transition<R> apply(AppState current);
}
