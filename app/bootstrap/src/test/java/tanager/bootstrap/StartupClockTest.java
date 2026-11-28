package tanager.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.state.FakeClock;

/** 여러 스레드에서 오는 시작 단계를 모아 한 번만 알린다(#221). */
class StartupClockTest {
    private static final Instant JVM_START = Instant.parse("2026-11-28T00:00:00Z");

    private final FakeClock time = new FakeClock(JVM_START);
    private final StartupClock clock = new StartupClock(JVM_START, time);

    @Test
    @DisplayName("모든 단계가 기록되면 그때까지의 한 줄을 알린다")
    void reportsWhenComplete() {
        // given
        CompletableFuture<String> reported = clock.reported().toCompletableFuture();

        // when
        for (StartupPhase phase : StartupPhase.values()) {
            assertThat(reported).isNotDone();
            time.advance(Duration.ofMillis(100));
            clock.mark(phase);
        }

        // then
        assertThat(reported)
                .isCompletedWithValue("startup: main=100ms state=200ms shells=300ms window=400ms firstByte=500ms");
    }

    @Test
    @DisplayName("기한이 되면 빠진 단계를 - 로 두고 알린다")
    void deadlineReportsMissingPhases() {
        // given
        time.advance(Duration.ofMillis(120));
        clock.mark(StartupPhase.MAIN);
        time.advance(Duration.ofMillis(800));
        clock.mark(StartupPhase.WINDOW);

        // when
        clock.deadline();

        // then
        assertThat(clock.reported().toCompletableFuture())
                .isCompletedWithValue("startup: main=120ms state=- shells=- window=920ms firstByte=-");
    }

    @Test
    @DisplayName("알린 뒤의 기록 · 기한은 무시한다")
    void reportsOnce() {
        // given
        clock.mark(StartupPhase.MAIN);
        clock.deadline();

        // when
        time.advance(Duration.ofMillis(300));
        for (StartupPhase phase : StartupPhase.values()) {
            clock.mark(phase);
        }
        clock.deadline();

        // then
        assertThat(clock.reported().toCompletableFuture())
                .isCompletedWithValue("startup: main=0ms state=- shells=- window=- firstByte=-");
    }
}
