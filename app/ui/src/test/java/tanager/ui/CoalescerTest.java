package tanager.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 몰려오는 창 이벤트를 250ms 모아 한 번만 부른다(FR-RES-03, #180). 시간은 손으로 돌리는 시계 · 타이머로 결정적으로 본다. */
class CoalescerTest {
    private static final Duration QUIET = Duration.ofMillis(250);

    /** 손으로 돌리는 시계 겸 일회용 타이머. */
    private static final class ManualTime implements Coalescer.Timer {
        private record Due(long at, Runnable task) {}

        private final List<Due> due = new ArrayList<>();
        private long now;

        long nanos() {
            return now;
        }

        @Override
        public void after(Duration delay, Runnable task) {
            due.add(new Due(now + delay.toNanos(), task));
        }

        void advance(Duration amount) {
            long until = now + amount.toNanos();
            while (true) {
                Due next = due.stream()
                        .filter(d -> d.at() <= until)
                        .min(Comparator.comparingLong(Due::at))
                        .orElse(null);
                if (next == null) {
                    break;
                }
                due.remove(next);
                now = next.at();
                next.task().run();
            }
            now = until;
        }
    }

    private final ManualTime time = new ManualTime();
    private final AtomicInteger runs = new AtomicInteger();
    private final Coalescer coalescer = new Coalescer(QUIET, time, time::nanos, runs::incrementAndGet);

    @Test
    @DisplayName("창 크기를 끄는 중 300ms 동안 30번 바뀌면 부르는 것은 1~2번이다")
    void thirtyChangesInThreeHundredMillisRunOnceOrTwice() {
        // when
        for (int i = 0; i < 30; i++) {
            coalescer.poke();
            time.advance(Duration.ofMillis(10));
        }
        time.advance(Duration.ofSeconds(1));

        // then
        assertThat(runs.get()).isBetween(1, 2);
    }

    @Test
    @DisplayName("한 번 바뀌면 250ms 가 지나야 부른다")
    void runsAfterQuietPeriod() {
        // when
        coalescer.poke();
        time.advance(Duration.ofMillis(249));
        int before = runs.get();
        time.advance(Duration.ofMillis(1));

        // then
        assertThat(before).isZero();
        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("부른 뒤 다시 바뀌면 다시 250ms 뒤에 한 번 부른다")
    void pokeAfterRunSchedulesAgain() {
        // given
        coalescer.poke();
        time.advance(QUIET);

        // when
        coalescer.poke();
        time.advance(QUIET);

        // then
        assertThat(runs.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("기다리는 중에 flush 하면 바로 한 번 부르고, 걸려 있던 타이머는 다시 부르지 않는다")
    void flushRunsPendingAtOnce() {
        // given
        coalescer.poke();

        // when
        coalescer.flush();
        int afterFlush = runs.get();
        time.advance(Duration.ofSeconds(1));

        // then
        assertThat(afterFlush).isEqualTo(1);
        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("기다리는 일이 없으면 flush 는 부르지 않는다")
    void flushWithoutPendingDoesNothing() {
        // when
        coalescer.flush();

        // then
        assertThat(runs.get()).isZero();
    }

    @Test
    @DisplayName("flush 뒤에 바뀐 것은 이전 타이머가 아니라 새 250ms 뒤에 한 번 부른다")
    void pokeAfterFlushWaitsFullQuietPeriod() {
        // given
        coalescer.poke();
        time.advance(Duration.ofMillis(100));
        coalescer.flush();

        // when
        coalescer.poke();
        time.advance(Duration.ofMillis(200));
        int beforeQuiet = runs.get();
        time.advance(Duration.ofSeconds(1));

        // then
        assertThat(beforeQuiet).isEqualTo(1);
        assertThat(runs.get()).isEqualTo(2);
    }
}
