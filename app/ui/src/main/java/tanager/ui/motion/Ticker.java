package tanager.ui.motion;

/** 화면 프레임마다 콜백을 부르는 박자. 실제는 {@link SwingTicker}(EDT), 테스트는 손으로 넘긴다. */
interface Ticker {

    /** 프레임마다 {@code onTick} 을 부르기 시작한다. 이미 돌고 있으면 콜백만 바꾼다. */
    void start(Runnable onTick);

    /** 멈춘다. 멈춰 있으면 아무 일도 하지 않는다. */
    void stop();
}
