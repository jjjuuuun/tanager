package tanager.application.port.out;

/** 구독 해제 손잡이. 터미널 출력({@link TerminalStream#subscribe})과 상태 이벤트 구독이 함께 쓴다. */
@FunctionalInterface
public interface Subscription {

    /** 구독을 끊는다. 여러 번 불러도 되고 어느 스레드에서 불러도 된다. 이미 전달 중인 조각 · 이벤트 하나는 받을 수 있다. */
    void cancel();
}
