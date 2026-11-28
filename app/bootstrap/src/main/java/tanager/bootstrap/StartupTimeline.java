package tanager.bootstrap;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * JVM 시작부터 단계마다 걸린 시간(NFR-02, #221). 불변이다 — 기록은 새 값을 돌려주고, 같은 단계는 처음 기록을 지킨다.
 *
 * @param origin JVM 시작 시각. 값은 모두 여기서부터 잰다
 */
record StartupTimeline(Instant origin, Map<StartupPhase, Instant> marks) {

    StartupTimeline {
        Objects.requireNonNull(origin, "origin");
        marks = Map.copyOf(marks);
    }

    static StartupTimeline startingAt(Instant origin) {
        return new StartupTimeline(origin, Map.of());
    }

    StartupTimeline mark(StartupPhase phase, Instant at) {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(at, "at");
        if (marks.containsKey(phase)) {
            return this;
        }
        Map<StartupPhase, Instant> next = new EnumMap<>(StartupPhase.class);
        next.putAll(marks);
        next.put(phase, at);
        return new StartupTimeline(origin, next);
    }

    /** 모든 단계를 기록했다. */
    boolean complete() {
        return marks.size() == StartupPhase.values().length;
    }

    /** 예: {@code startup: main=180ms state=192ms shells=640ms window=1130ms firstByte=1420ms}. 없는 단계는 {@code -}. */
    String line() {
        StringJoiner line = new StringJoiner(" ", "startup: ", "");
        for (StartupPhase phase : StartupPhase.values()) {
            String value = marks.containsKey(phase) ? elapsedMillis(marks.get(phase)) + "ms" : "-";
            line.add(phase.key() + "=" + value);
        }
        return line.toString();
    }

    // 프로세스 시작 시각은 OS 해상도로 잘려 첫 기록보다 늦을 수 있다 — 음수 대신 0
    private long elapsedMillis(Instant at) {
        return Math.max(0, Duration.between(origin, at).toMillis());
    }
}
