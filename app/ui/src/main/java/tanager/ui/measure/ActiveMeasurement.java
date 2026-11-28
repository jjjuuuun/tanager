package tanager.ui.measure;

import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import tanager.domain.shared.PaneId;

/**
 * 켜진 측정 모드. 창마다 {@link InputLatencyProbe} 하나, EDT 정지 감시({@link EdtStallWatch}) 하나. 타이머는 Swing 타이머(EDT)이고
 * 정지 중 스택을 뜨는 감시만 데몬 플랫폼 스레드 하나다. 앱과 수명이 같아 멈추지 않는다.
 */
final class ActiveMeasurement implements Measurement {
    static final Duration REPORT_EVERY = Duration.ofSeconds(30);
    private static final System.Logger LOG = System.getLogger(ActiveMeasurement.class.getName());

    private final LongSupplier nanos;
    // 닫힌 창의 프로브도 남는다 — 창 번호 하나에 하나라 측정 모드에서만 조금 는다
    private final Map<PaneId, InputLatencyProbe> panes = new ConcurrentHashMap<>();

    ActiveMeasurement(LongSupplier nanos) {
        this.nanos = Objects.requireNonNull(nanos, "nanos");
    }

    @Override
    public PaneMeter pane(PaneId pane) {
        Objects.requireNonNull(pane, "pane");
        return panes.computeIfAbsent(pane, id -> new InputLatencyProbe(id, nanos));
    }

    @Override
    public void start() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("측정 모드는 EDT 에서 시작한다(버그)");
        }
        Thread edt = Thread.currentThread();
        EdtStallWatch stalls = new EdtStallWatch(nanos, edt::getStackTrace, ActiveMeasurement::log);
        Timer ticks = new Timer((int) EdtStallWatch.PERIOD.toMillis(), _ -> stalls.ticked());
        ticks.setCoalesce(true);
        ticks.start();
        ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor(task ->
                Thread.ofPlatform().name("tanager-measure-edt").daemon(true).unstarted(task));
        long period = EdtStallWatch.PERIOD.toNanos();
        watcher.scheduleAtFixedRate(stalls::watch, period, period, TimeUnit.NANOSECONDS);
        Timer reports = new Timer((int) REPORT_EVERY.toMillis(), _ -> report());
        reports.start();
        LOG.log(Level.INFO, "측정 모드 — 입력 반응(30초마다) · EDT 정지(100ms 넘게)를 로그로 남긴다");
    }

    private void report() {
        for (InputLatencyProbe probe : panes.values()) {
            probe.report().ifPresent(ActiveMeasurement::log);
        }
    }

    private static void log(String line) {
        LOG.log(Level.INFO, line);
    }
}
