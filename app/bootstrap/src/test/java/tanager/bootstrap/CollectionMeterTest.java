package tanager.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.out.FakeResourceProbe;
import tanager.application.port.out.FakeScheduler;
import tanager.application.port.out.ResourceProbe;

/** 측정 모드의 수집 부하 계측(NFR-10, #222). 시계 · 스레드 CPU 시간 · 예약은 가짜, 수집 일은 부른 스레드에서 바로 돈다. */
class CollectionMeterTest {
    private final AtomicLong nanos = new AtomicLong();
    private final AtomicLong cpu = new AtomicLong();
    private final FakeScheduler scheduler = new FakeScheduler();
    private final List<String> log = new ArrayList<>();
    private final Executor direct = Runnable::run;

    @Test
    @DisplayName("60초마다 수집 일의 CPU 시간과 wsl.exe 호출 간격 최솟값을 한 줄로 남긴다")
    void report_everyMinute() {
        // given
        CollectionMeter meter = meter(Optional.of(cpu::get));
        ResourceProbe probe = meter.probe(new FakeResourceProbe());
        Executor io = meter.io();

        // when — 일 두 개가 CPU 3ms 씩 쓰고, 그 안에서 wsl.exe 를 5초 간격으로 부른다
        io.execute(() -> {
            probe.wsl("Ubuntu", Duration.ofSeconds(1));
            cpu.addAndGet(millis(3));
        });
        nanos.addAndGet(millis(5_000));
        io.execute(() -> {
            probe.wsl("Ubuntu", Duration.ofSeconds(1));
            cpu.addAndGet(millis(3));
        });
        nanos.set(millis(60_000));
        scheduler.advance(CollectionMeter.REPORT_EVERY);

        // then
        assertThat(log)
                .containsExactly(
                        "resource-load window=60s tasks=2 cpuCore=0.01% cpuAll=0.00% wslCalls=2 wslMinGap=5000ms");
    }

    @Test
    @DisplayName("보고 뒤에는 새 구간을 센다")
    void report_startsNextWindow() {
        // given
        CollectionMeter meter = meter(Optional.of(cpu::get));
        meter.io().execute(() -> cpu.addAndGet(millis(6)));
        nanos.set(millis(60_000));
        scheduler.advance(CollectionMeter.REPORT_EVERY);

        // when
        nanos.set(millis(120_000));
        scheduler.advance(CollectionMeter.REPORT_EVERY);

        // then
        assertThat(log).hasSize(2);
        assertThat(log.get(1))
                .isEqualTo("resource-load window=60s tasks=0 cpuCore=0.00% cpuAll=0.00% wslCalls=0 wslMinGap=-");
    }

    @Test
    @DisplayName("스레드 CPU 시간을 잴 수 없으면 측정 불가로 적고 일은 그대로 돈다")
    void io_withoutThreadCpu_stillRunsTasks() {
        // given
        CollectionMeter meter = meter(Optional.empty());
        List<String> ran = new ArrayList<>();

        // when
        meter.io().execute(() -> ran.add("sample"));
        nanos.set(millis(60_000));
        scheduler.advance(CollectionMeter.REPORT_EVERY);

        // then
        assertThat(ran).containsExactly("sample");
        assertThat(log)
                .containsExactly(
                        "resource-load window=60s tasks=1 cpuCore=unavailable cpuAll=unavailable wslCalls=0 wslMinGap=-");
    }

    private CollectionMeter meter(Optional<LongSupplier> threadCpu) {
        return CollectionMeter.start(nanos::get, threadCpu, 8, direct, scheduler, log::add);
    }

    private static long millis(long value) {
        return TimeUnit.MILLISECONDS.toNanos(value);
    }
}
