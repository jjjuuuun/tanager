package dev.tanager.application.port.out;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 시간을 손으로 진행하는 {@link Scheduler} Fake. {@link #advance} 가 기한이 된 일을 기한 순서대로 부른 스레드에서 돌린다. 도는 일이 새로
 * 예약한 일도 기한이 진행 범위 안이면 같은 호출에서 돈다. 스레드 안전.
 */
public final class FakeScheduler implements Scheduler {
    private final List<Entry> entries = new ArrayList<>();
    private Duration now = Duration.ZERO;
    private long sequence;

    private static final class Entry {
        final Duration due;
        final long order;
        final Runnable task;
        boolean cancelled;

        Entry(Duration due, long order, Runnable task) {
            this.due = due;
            this.order = order;
            this.task = task;
        }
    }

    @Override
    public synchronized Cancellable schedule(Duration delay, Runnable task) {
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(task, "task");
        Entry entry = new Entry(now.plus(delay), sequence++, task);
        entries.add(entry);
        return () -> {
            synchronized (FakeScheduler.this) {
                entry.cancelled = true;
                entries.remove(entry);
            }
        };
    }

    /** 시간을 {@code amount} 만큼 진행하며 기한이 된 일을 돌린다. */
    public void advance(Duration amount) {
        Duration target;
        synchronized (this) {
            target = now.plus(amount);
        }
        while (true) {
            Entry next;
            synchronized (this) {
                next = entries.stream()
                        .filter(e -> e.due.compareTo(target) <= 0)
                        .min(Comparator.comparing((Entry e) -> e.due).thenComparingLong(e -> e.order))
                        .orElse(null);
                if (next == null) {
                    now = target;
                    return;
                }
                entries.remove(next);
                if (next.due.compareTo(now) > 0) {
                    now = next.due;
                }
            }
            if (!next.cancelled) {
                next.task.run();
            }
        }
    }

    /** 아직 돌지 않았고 취소되지 않은 예약의 수. */
    public synchronized int pendingCount() {
        return entries.size();
    }

    /** 가장 이른 예약까지 남은 시간(없으면 null 대신 빈 값). */
    public synchronized java.util.Optional<Duration> untilNext() {
        return entries.stream().map(e -> e.due.minus(now)).min(Comparator.naturalOrder());
    }
}
