package tanager.ui.motion;

import java.util.Optional;

/** 테스트가 직접 프레임을 넘기는 {@link Ticker}. 시계는 {@link #advance} 로만 움직인다. */
final class ManualTicker implements Ticker {
    private Optional<Runnable> onTick = Optional.empty();
    private long now;
    private int starts;

    @Override
    public void start(Runnable onTick) {
        if (this.onTick.isEmpty()) {
            starts++;
        }
        this.onTick = Optional.of(onTick);
    }

    @Override
    public void stop() {
        onTick = Optional.empty();
    }

    long nanoTime() {
        return now;
    }

    boolean running() {
        return onTick.isPresent();
    }

    /** 멈춘 상태에서 시작한 횟수. */
    int starts() {
        return starts;
    }

    /** 시계를 {@code millis} 만큼 움직이고, 돌고 있으면 한 프레임을 보낸다. */
    void advance(long millis) {
        now += millis * 1_000_000L;
        onTick.ifPresent(Runnable::run);
    }
}
