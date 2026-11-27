package tanager.ui;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * 몰려오는 요청을 모아 마지막 요청 뒤 {@code quiet} 동안 조용하면 한 번 부른다(FR-RES-03, #180). 창을 끄는 동안 오는 수십 번의 이동 ·
 * 크기 이벤트를 한 번의 기록으로 줄인다. EDT 에서만 쓴다 — 타이머도 EDT 에서 불러야 한다.
 */
final class Coalescer {
    /** 일회용 타이머: {@code delay} 뒤 {@code task} 를 한 번 부른다. */
    @FunctionalInterface
    interface Timer {
        void after(Duration delay, Runnable task);

        /** Swing 타이머(EDT 에서 부른다). */
        static Timer swing() {
            return (delay, task) -> {
                // 올림 — 남은 1ms 미만을 0 으로 걸어 바로 다시 깨는 일이 없게
                int millis = (int) Math.max(1, (delay.toNanos() + 999_999) / 1_000_000);
                javax.swing.Timer timer = new javax.swing.Timer(millis, event -> task.run());
                timer.setRepeats(false);
                timer.start();
            };
        }
    }

    private final Duration quiet;
    private final Timer timer;
    private final LongSupplier nanoTime;
    private final Runnable action;
    // 걸어 둔 타이머의 세대. flush 하면 올려서 이미 걸린 타이머가 와도 무시한다
    private long generation;
    private boolean pending;
    private long lastPoke;

    Coalescer(Duration quiet, Timer timer, LongSupplier nanoTime, Runnable action) {
        this.quiet = Objects.requireNonNull(quiet, "quiet");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.action = Objects.requireNonNull(action, "action");
    }

    /** 바뀌었다. 이미 기다리는 중이면 기다림을 늘리기만 한다 — 타이머를 새로 걸지 않는다. */
    void poke() {
        lastPoke = nanoTime.getAsLong();
        if (!pending) {
            pending = true;
            long armed = ++generation;
            timer.after(quiet, () -> due(armed));
        }
    }

    /** 기다리는 일이 있으면 바로 부른다(창을 닫을 때). */
    void flush() {
        if (pending) {
            pending = false;
            generation++;
            action.run();
        }
    }

    private void due(long armed) {
        if (!pending || armed != generation) {
            return;
        }
        long waited = nanoTime.getAsLong() - lastPoke;
        if (waited >= quiet.toNanos()) {
            pending = false;
            action.run();
        } else {
            timer.after(Duration.ofNanos(quiet.toNanos() - waited), () -> due(armed));
        }
    }
}
