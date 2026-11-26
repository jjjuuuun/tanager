package dev.tanager.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.tanager.application.port.out.Cancellable;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 운영 스케줄러: 실제 시간을 쓰므로 짧은 지연과 Awaitility 로 본다. */
class ExecutorSchedulerTest {
    private final ExecutorScheduler scheduler = new ExecutorScheduler("test-timer");

    @AfterEach
    void close() {
        scheduler.close();
    }

    @Test
    @DisplayName("예약한 일은 지연이 지난 뒤 한 번만 돈다")
    void runsOnceAfterDelay() throws Exception {
        // given
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch ran = new CountDownLatch(1);
        long started = System.nanoTime();
        long[] ranAt = new long[1];

        // when
        scheduler.schedule(Duration.ofMillis(100), () -> {
            ranAt[0] = System.nanoTime();
            runs.incrementAndGet();
            ran.countDown();
        });

        // then
        assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(Duration.ofNanos(ranAt[0] - started)).isGreaterThanOrEqualTo(Duration.ofMillis(100));
        // 반복 예약이 아님을 다음 예약이 돈 뒤에 본다(같은 스레드라 순서대로 돈다)
        CountDownLatch later = new CountDownLatch(1);
        scheduler.schedule(Duration.ofMillis(200), later::countDown);
        assertThat(later.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(runs).hasValue(1);
    }

    @Test
    @DisplayName("취소한 예약은 돌지 않는다")
    void cancelledTaskDoesNotRun() throws Exception {
        // given
        AtomicBoolean ran = new AtomicBoolean();
        Cancellable timer = scheduler.schedule(Duration.ofMillis(100), () -> ran.set(true));

        // when
        timer.cancel();

        // then 뒤에 예약한 일이 돈 뒤에도 돌지 않았다
        CountDownLatch later = new CountDownLatch(1);
        scheduler.schedule(Duration.ofMillis(300), later::countDown);
        assertThat(later.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(ran).isFalse();
    }

    @Test
    @DisplayName("닫은 뒤의 예약은 예외 없이 받지만 돌지 않고, 취소해도 된다")
    void scheduleAfterCloseIsIgnored() throws Exception {
        // given
        scheduler.close();
        AtomicBoolean ran = new AtomicBoolean();

        // when
        Cancellable timer = scheduler.schedule(Duration.ZERO, () -> ran.set(true));
        timer.cancel();

        // then
        await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(1)).untilFalse(ran);
    }

    @Test
    @DisplayName("예약한 일은 데몬 스레드에서 돈다(앱 종료를 막지 않는다)")
    void runsOnDaemonThread() throws Exception {
        // given
        AtomicBoolean daemon = new AtomicBoolean();
        AtomicReference<String> name = new AtomicReference<>("");
        CountDownLatch ran = new CountDownLatch(1);

        // when
        scheduler.schedule(Duration.ZERO, () -> {
            daemon.set(Thread.currentThread().isDaemon());
            name.set(Thread.currentThread().getName());
            ran.countDown();
        });

        // then
        assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(daemon).isTrue();
        assertThat(name).hasValue("test-timer");
    }
}
