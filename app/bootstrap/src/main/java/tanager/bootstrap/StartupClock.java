package tanager.bootstrap;

import java.time.Instant;
import java.time.InstantSource;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 여러 스레드(main · 가상 스레드 · EDT · PTY 읽기)에서 오는 시작 단계를 모아 한 줄을 한 번만 알린다(#221). 모든 단계가 모이거나
 * {@link #deadline} 이 오면 알린다 — 셸을 고르기 전(셸 선택 화면) · 셸 탐지 실패처럼 끝내 오지 않는 단계가 있다.
 */
final class StartupClock {
    private final InstantSource time;
    private final AtomicReference<StartupTimeline> timeline;
    private final CompletableFuture<String> reported = new CompletableFuture<>();

    StartupClock(Instant origin, InstantSource time) {
        this.time = Objects.requireNonNull(time, "time");
        this.timeline = new AtomicReference<>(StartupTimeline.startingAt(origin));
    }

    /** 이 프로세스의 시작 시각부터 잰다(java.management 없이 — jlink 런타임 모듈을 늘리지 않는다). 시작 시각을 모르면 지금부터. */
    static StartupClock sinceProcessStart() {
        InstantSource system = InstantSource.system();
        Instant origin = ProcessHandle.current().info().startInstant().orElseGet(system::instant);
        return new StartupClock(origin, system);
    }

    /** 지금을 {@code phase} 의 시각으로 기록한다. 이미 기록한 단계 · 알린 뒤의 기록은 무시한다. */
    void mark(StartupPhase phase) {
        Instant now = time.instant();
        StartupTimeline next = timeline.updateAndGet(current -> current.mark(phase, now));
        if (next.complete()) {
            reported.complete(next.line());
        }
    }

    /** 기다리지 않고 지금까지의 단계로 알린다. 이미 알렸으면 아무 일도 하지 않는다. */
    void deadline() {
        reported.complete(timeline.get().line());
    }

    /** 알린 한 줄. 한 번만 완료된다. */
    CompletionStage<String> reported() {
        return reported.minimalCompletionStage();
    }
}
