package tanager.application.port.out;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
            Optional<Entry> due = nextDue(target);
            if (due.isEmpty()) {
                return;
            }
            Entry next = due.get();
            if (!next.cancelled) {
                next.task.run();
            }
        }
    }

    // 기한이 target 안인 가장 이른 일을 꺼내며 시간을 그 기한으로 옮긴다. 없으면 시간을 target 으로 옮기고 빈 값
    private synchronized Optional<Entry> nextDue(Duration target) {
        Optional<Entry> next = entries.stream()
                .filter(e -> e.due.compareTo(target) <= 0)
                .min(Comparator.comparing((Entry e) -> e.due).thenComparingLong(e -> e.order));
        if (next.isEmpty()) {
            now = target;
            return next;
        }
        Entry entry = next.get();
        entries.remove(entry);
        if (entry.due.compareTo(now) > 0) {
            now = entry.due;
        }
        return next;
    }

    /** 아직 돌지 않았고 취소되지 않은 예약의 수. */
    public synchronized int pendingCount() {
        return entries.size();
    }

    /** 가장 이른 예약까지 남은 시간. 예약이 없으면 빈 값. */
    public synchronized Optional<Duration> untilNext() {
        return entries.stream().map(e -> e.due.minus(now)).min(Comparator.naturalOrder());
    }
}
