package tanager.application.state;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** 손으로 진행하는 {@link InstantSource}. 스레드 안전. */
public final class FakeClock implements InstantSource {
    private final AtomicReference<Instant> now;

    public FakeClock(Instant start) {
        this.now = new AtomicReference<>(Objects.requireNonNull(start, "start"));
    }

    /** 시각 0 에서 시작한다. */
    public static FakeClock atEpoch() {
        return new FakeClock(Instant.EPOCH);
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    public void advance(Duration amount) {
        now.updateAndGet(current -> current.plus(amount));
    }
}
