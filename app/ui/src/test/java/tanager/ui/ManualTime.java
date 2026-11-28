package tanager.ui;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** 손으로 돌리는 시계 겸 일회용 타이머. {@link #advance} 가 그 사이에 걸린 타이머를 시각 순서로 부른다. 한 스레드에서만 쓴다. */
final class ManualTime implements Coalescer.Timer, InstantSource {
    private record Due(long at, Runnable task) {}

    private final List<Due> due = new ArrayList<>();
    private long now;

    /** 시작부터 흐른 나노초. */
    long nanos() {
        return now;
    }

    /** {@link Instant#EPOCH} 에서 {@link #nanos()} 만큼 지난 시각. */
    @Override
    public Instant instant() {
        return Instant.EPOCH.plusNanos(now);
    }

    @Override
    public void after(Duration delay, Runnable task) {
        due.add(new Due(now + delay.toNanos(), task));
    }

    void advance(Duration amount) {
        long until = now + amount.toNanos();
        while (true) {
            Optional<Due> next = due.stream().filter(d -> d.at() <= until).min(Comparator.comparingLong(Due::at));
            if (next.isEmpty()) {
                break;
            }
            due.remove(next.get());
            now = next.get().at();
            next.get().task().run();
        }
        now = until;
    }
}
