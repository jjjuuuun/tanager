package tanager.bootstrap;

import java.lang.System.Logger.Level;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import tanager.application.port.out.ResourceProbe;
import tanager.application.port.out.Scheduler;

/**
 * 측정 모드(#222)에서 리소스 수집 자체의 부하를 잰다(NFR-10). {@code ResourceWatcher} 는 바꾸지 않는다 — 조립 루트가 그 출력 포트(수집기 ·
 * I/O 실행기)를 감싼다. 꺼져 있으면 만들지 않는다.
 *
 * <ul>
 *   <li>수집 일은 가상 스레드 대신 데몬 플랫폼 스레드 하나에서 돈다 — 스레드 CPU 시간({@link ThreadMXBean})은 가상 스레드에서 -1 이다.
 *       일마다 앞뒤 CPU 시간의 차이를 더한다.
 *   <li>{@code wsl.exe} 호출 시각은 수집기({@link ResourceProbe#wsl})를 감싸 기록한다.
 *   <li>{@link #REPORT_EVERY} 마다 한 줄: {@code resource-load window=60s tasks=… cpuCore=…% cpuAll=…% wslCalls=…
 *       wslMinGap=…ms}.
 * </ul>
 *
 * <p>외부 명령({@code wsl.exe} 와 그 안의 {@code sh})의 CPU 는 다른 프로세스라 세지 않는다.
 */
final class CollectionMeter {
    static final Duration REPORT_EVERY = Duration.ofSeconds(60);
    private static final System.Logger LOG = System.getLogger(CollectionMeter.class.getName());

    private final LongSupplier nanos;
    private final Optional<LongSupplier> threadCpu;
    private final int cores;
    private final Executor thread;
    private final Scheduler scheduler;
    private final Consumer<String> log;
    private final AtomicReference<CollectionLoad> load;

    private CollectionMeter(
            LongSupplier nanos,
            Optional<LongSupplier> threadCpu,
            int cores,
            Executor thread,
            Scheduler scheduler,
            Consumer<String> log) {
        this.nanos = Objects.requireNonNull(nanos, "nanos");
        this.threadCpu = Objects.requireNonNull(threadCpu, "threadCpu");
        this.cores = cores;
        this.thread = Objects.requireNonNull(thread, "thread");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.log = Objects.requireNonNull(log, "log");
        this.load = new AtomicReference<>(CollectionLoad.startingAt(nanos.getAsLong()));
    }

    /** 운영 구성: 시스템 시계 · 이 JVM 의 스레드 CPU 시간 · 데몬 플랫폼 스레드 하나. 보고 예약은 {@code scheduler}. */
    static CollectionMeter start(Scheduler scheduler) {
        Optional<LongSupplier> cpu = currentThreadCpu();
        if (cpu.isEmpty()) {
            LOG.log(Level.WARNING, "측정 불가 — 이 런타임은 스레드 CPU 시간을 주지 않는다. 수집 부하는 호출 수 · 간격만 적는다");
        }
        Executor thread = Executors.newSingleThreadExecutor(
                task -> Thread.ofPlatform().name("tanager-monitor").daemon(true).unstarted(task));
        return start(
                System::nanoTime,
                cpu,
                Runtime.getRuntime().availableProcessors(),
                thread,
                scheduler,
                line -> LOG.log(Level.INFO, line));
    }

    /** 부품을 받는다(테스트). */
    static CollectionMeter start(
            LongSupplier nanos,
            Optional<LongSupplier> threadCpu,
            int cores,
            Executor thread,
            Scheduler scheduler,
            Consumer<String> log) {
        CollectionMeter meter = new CollectionMeter(nanos, threadCpu, cores, thread, scheduler, log);
        meter.scheduleReport();
        LOG.log(Level.INFO, "측정 모드 — 리소스 수집 부하를 {0}초마다 로그로 남긴다", REPORT_EVERY.toSeconds());
        return meter;
    }

    /** {@code wsl.exe} 호출을 기록하는 수집기. */
    ResourceProbe probe(ResourceProbe delegate) {
        return new MeasuredResourceProbe(
                delegate, () -> load.updateAndGet(current -> current.wslCalled(nanos.getAsLong())));
    }

    /** 수집 일마다 CPU 시간을 재는 실행기. */
    Executor io() {
        return task -> {
            Objects.requireNonNull(task, "task");
            thread.execute(() -> measured(task));
        };
    }

    private void measured(Runnable task) {
        OptionalLong before = cpuNow();
        try {
            task.run();
        } finally {
            OptionalLong after = cpuNow();
            load.updateAndGet(current -> before.isPresent() && after.isPresent()
                    ? current.collected(after.getAsLong() - before.getAsLong())
                    : current.collectedWithoutCpu());
        }
    }

    // 이 스레드의 CPU 시간. 잴 수 없으면(미지원 · 이 스레드는 -1) 비어 있다
    private OptionalLong cpuNow() {
        if (threadCpu.isEmpty()) {
            return OptionalLong.empty();
        }
        long value = threadCpu.get().getAsLong();
        return value < 0 ? OptionalLong.empty() : OptionalLong.of(value);
    }

    private void scheduleReport() {
        scheduler.schedule(REPORT_EVERY, this::report);
    }

    private void report() {
        long now = nanos.getAsLong();
        CollectionLoad finished = load.getAndUpdate(current -> current.next(now));
        log.accept(finished.line(now, cores));
        scheduleReport();
    }

    // 지원하지 않거나 모듈(java.management)이 없는 런타임이면 비어 있다
    private static Optional<LongSupplier> currentThreadCpu() {
        try {
            ThreadMXBean threads = ManagementFactory.getThreadMXBean();
            if (!threads.isCurrentThreadCpuTimeSupported()) {
                return Optional.empty();
            }
            if (!threads.isThreadCpuTimeEnabled()) {
                threads.setThreadCpuTimeEnabled(true);
            }
            return Optional.of(threads::getCurrentThreadCpuTime);
        } catch (LinkageError | UnsupportedOperationException | SecurityException e) {
            LOG.log(Level.DEBUG, "스레드 CPU 시간을 읽을 수 없다", e);
            return Optional.empty();
        }
    }
}
