package dev.tanager.bootstrap;

import dev.tanager.application.port.out.Cancellable;
import dev.tanager.application.port.out.Scheduler;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** {@link Scheduler} 의 운영 구현. 데몬 플랫폼 스레드 하나라 앱 종료를 막지 않는다. 예약한 일은 짧게 끝나야 한다. */
final class ExecutorScheduler implements Scheduler, AutoCloseable {
    private final ScheduledExecutorService executor;

    ExecutorScheduler(String threadName) {
        Objects.requireNonNull(threadName, "threadName");
        this.executor = Executors.newSingleThreadScheduledExecutor(
                task -> Thread.ofPlatform().name(threadName).daemon(true).unstarted(task));
    }

    @Override
    public Cancellable schedule(Duration delay, Runnable task) {
        Objects.requireNonNull(task, "task");
        try {
            var future = executor.schedule(task, delay.toNanos(), TimeUnit.NANOSECONDS);
            return () -> future.cancel(false);
        } catch (RejectedExecutionException e) {
            // 닫은 뒤(종료 중)의 예약은 돌지 않는다 — 종료 때 저장은 flush 가 맡는다
            return () -> {};
        }
    }

    /** 남은 예약을 버린다. */
    @Override
    public void close() {
        executor.shutdownNow();
    }
}
