package tanager.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 앱 종료 순서(ADR-0011 개정): 종료 훅이나 셸이 멈춰도 기한이 지나면 프로세스를 끝낸다. */
class ExitSequenceTest {
    private final AtomicInteger halts = new AtomicInteger();

    private static long deadlineIn(Duration amount) {
        return System.nanoTime() + amount.toNanos();
    }

    @Test
    @DisplayName("종료 훅이 끝나지 않아도 기한이 지나면 프로세스를 끝낸다")
    void hungHookDoesNotDelayExitPastDeadline() {
        // given
        CountDownLatch never = new CountDownLatch(1);
        Runnable hung = () -> {
            try {
                never.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        long started = System.nanoTime();

        // when
        ExitSequence.run(
                hung,
                CompletableFuture.completedFuture(null),
                deadlineIn(Duration.ofMillis(200)),
                halts::incrementAndGet);

        // then
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        assertThat(halts).hasValue(1);
        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(150)).isLessThan(Duration.ofSeconds(3));
        never.countDown();
    }

    @Test
    @DisplayName("셸이 끝나지 않아도 기한이 지나면 프로세스를 끝낸다")
    void shellThatNeverExitsDoesNotDelayExitPastDeadline() {
        // given
        long started = System.nanoTime();

        // when
        ExitSequence.run(
                () -> {}, new CompletableFuture<>(), deadlineIn(Duration.ofMillis(200)), halts::incrementAndGet);

        // then
        assertThat(halts).hasValue(1);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("종료 훅이 끝나고 셸이 끝나면 기한을 기다리지 않고 바로 끝낸다")
    void finishesAsSoonAsHookAndShellAreDone() {
        // given
        AtomicInteger hookRuns = new AtomicInteger();
        long started = System.nanoTime();

        // when
        ExitSequence.run(
                hookRuns::incrementAndGet,
                CompletableFuture.completedFuture(null),
                deadlineIn(Duration.ofSeconds(30)),
                halts::incrementAndGet);

        // then
        assertThat(hookRuns).hasValue(1);
        assertThat(halts).hasValue(1);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("종료 훅이 예외를 던져도 셸 종료를 기다린 뒤 끝낸다")
    void throwingHookStillExits() {
        // given
        CompletableFuture<Void> exited = new CompletableFuture<>();
        Runnable throwing = () -> {
            exited.complete(null);
            throw new IllegalStateException("훅 고장");
        };

        // when
        ExitSequence.run(throwing, exited, deadlineIn(Duration.ofSeconds(30)), halts::incrementAndGet);

        // then
        assertThat(halts).hasValue(1);
    }

    @Test
    @DisplayName("기다리는 중 Error 가 나도 프로세스를 끝낸다")
    void errorWhileWaitingStillExits() {
        // given 셸 종료를 기다리려 하면 Error 가 난다
        CompletionStage<Object> broken = new CompletableFuture<>() {
            @Override
            public CompletableFuture<Object> toCompletableFuture() {
                throw new AssertionError("고장");
            }
        };

        // when / then
        assertThatThrownBy(() ->
                        ExitSequence.run(() -> {}, broken, deadlineIn(Duration.ofSeconds(30)), halts::incrementAndGet))
                .isInstanceOf(AssertionError.class);
        assertThat(halts).hasValue(1);
    }

    @Test
    @DisplayName("종료 훅은 데몬 스레드에서 돈다(멈춘 훅이 JVM 을 붙잡지 않는다)")
    void hookRunsOnDaemonThread() throws Exception {
        // given
        CountDownLatch ran = new CountDownLatch(1);
        boolean[] daemon = new boolean[1];

        // when
        ExitSequence.run(
                () -> {
                    daemon[0] = Thread.currentThread().isDaemon();
                    ran.countDown();
                },
                CompletableFuture.completedFuture(null),
                deadlineIn(Duration.ofSeconds(30)),
                halts::incrementAndGet);

        // then
        assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(daemon[0]).isTrue();
    }
}
