package tanager.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** 시작 단계 기록과 로그 한 줄(NFR-02, #221). 시각은 가짜다 — JVM 시작 시각을 0 으로 두고 밀리초로 찍는다. */
class StartupTimelineTest {
    private static final Instant JVM_START = Instant.parse("2026-11-28T00:00:00Z");

    static Stream<Arguments> lines() {
        return Stream.of(
                Arguments.of(
                        "모든 단계",
                        marks(
                                StartupPhase.MAIN, 180,
                                StartupPhase.STATE, 192,
                                StartupPhase.SHELLS, 640,
                                StartupPhase.WINDOW, 1130,
                                StartupPhase.FIRST_BYTE, 1420),
                        "startup: main=180ms state=192ms shells=640ms window=1130ms firstByte=1420ms"),
                Arguments.of(
                        "셸 탐지 실패 — 탐지 · 첫 바이트가 없다",
                        marks(StartupPhase.MAIN, 150, StartupPhase.STATE, 170, StartupPhase.WINDOW, 900),
                        "startup: main=150ms state=170ms shells=- window=900ms firstByte=-"),
                Arguments.of(
                        "기록한 순서와 상관없이 단계 순서로 적는다",
                        marks(
                                StartupPhase.FIRST_BYTE, 2000,
                                StartupPhase.WINDOW, 1000,
                                StartupPhase.MAIN, 100,
                                StartupPhase.SHELLS, 1500,
                                StartupPhase.STATE, 120),
                        "startup: main=100ms state=120ms shells=1500ms window=1000ms firstByte=2000ms"),
                Arguments.of("아무것도 없음", marks(), "startup: main=- state=- shells=- window=- firstByte=-"),
                Arguments.of(
                        "기준보다 이른 시각은 0 으로 적는다(시작 시각 해상도)",
                        marks(StartupPhase.MAIN, -3),
                        "startup: main=0ms state=- shells=- window=- firstByte=-"),
                Arguments.of(
                        "밀리초 아래는 버린다",
                        Map.of(StartupPhase.MAIN, JVM_START.plusNanos(180_999_000)),
                        "startup: main=180ms state=- shells=- window=- firstByte=-"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lines")
    @DisplayName("단계마다 JVM 시작부터 걸린 시간을 한 줄로 적고 없는 단계는 - 로 둔다")
    void line(String name, Map<StartupPhase, Instant> marks, String expected) {
        // given
        StartupTimeline timeline = StartupTimeline.startingAt(JVM_START);
        for (Map.Entry<StartupPhase, Instant> mark : marks.entrySet()) {
            timeline = timeline.mark(mark.getKey(), mark.getValue());
        }

        // when
        String line = timeline.line();

        // then
        assertThat(line).isEqualTo(expected);
    }

    @Test
    @DisplayName("같은 단계를 다시 기록하면 처음 값을 지킨다")
    void firstMarkWins() {
        // given
        StartupTimeline timeline = StartupTimeline.startingAt(JVM_START).mark(StartupPhase.WINDOW, at(1000));

        // when
        StartupTimeline again = timeline.mark(StartupPhase.WINDOW, at(5000));

        // then
        assertThat(again.line()).contains("window=1000ms");
    }

    @Test
    @DisplayName("기록은 새 값을 돌려주고 원래 값은 그대로다")
    void markReturnsNewTimeline() {
        // given
        StartupTimeline empty = StartupTimeline.startingAt(JVM_START);

        // when
        empty.mark(StartupPhase.MAIN, at(100));

        // then
        assertThat(empty.line()).contains("main=-");
    }

    @Test
    @DisplayName("모든 단계가 기록돼야 완료다")
    void completeWhenEveryPhaseMarked() {
        // given
        StartupTimeline timeline = StartupTimeline.startingAt(JVM_START);
        StartupPhase[] phases = StartupPhase.values();

        // when · then
        for (int i = 0; i < phases.length; i++) {
            assertThat(timeline.complete()).as("%d 단계 기록 뒤", i).isFalse();
            timeline = timeline.mark(phases[i], at(100L * (i + 1)));
        }
        assertThat(timeline.complete()).isTrue();
    }

    private static Map<StartupPhase, Instant> marks(Object... phaseAndMillis) {
        Map<StartupPhase, Instant> marks = new LinkedHashMap<>();
        for (int i = 0; i < phaseAndMillis.length; i += 2) {
            marks.put((StartupPhase) phaseAndMillis[i], at(((Integer) phaseAndMillis[i + 1]).longValue()));
        }
        return marks;
    }

    private static Instant at(long millis) {
        return JVM_START.plusMillis(millis);
    }
}
