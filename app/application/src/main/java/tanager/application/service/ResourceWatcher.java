package tanager.application.service;

import java.lang.System.Logger.Level;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import tanager.application.port.in.AppEvent;
import tanager.application.port.in.ResourceReport;
import tanager.application.port.in.WatchResourcesUseCase;
import tanager.application.port.out.AppConfig;
import tanager.application.port.out.Cancellable;
import tanager.application.port.out.MonitorConfig;
import tanager.application.port.out.MonitorItem;
import tanager.application.port.out.ProbeError;
import tanager.application.port.out.ResourceProbe;
import tanager.application.port.out.Scheduler;
import tanager.application.port.out.Subscription;
import tanager.application.port.out.SystemTools;
import tanager.application.port.out.UiDispatcher;
import tanager.application.state.AppState;
import tanager.application.state.StateActor;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.monitor.CpuWindow;
import tanager.domain.monitor.DiskUsage;
import tanager.domain.monitor.HostUsage;
import tanager.domain.monitor.ResourceLevels;
import tanager.domain.monitor.ResourceSample;
import tanager.domain.monitor.Thresholds;
import tanager.domain.monitor.WslUsage;
import tanager.domain.session.LiveSession;
import tanager.domain.session.SessionState;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.Result;

/**
 * 주기(config {@code monitor.intervalSec})마다 리소스를 재서 UI 로 보낸다(SDD §6.6). 예약은 {@link Scheduler} 가, 재기는 I/O 실행기가 한다
 * — 앞 표본이 끝난 뒤에 다음 주기를 예약하므로 수집이 겹치지 않는다. 결과는 저장 상태가 아니라 StateActor 를 거치지 않고, 포커스 창 · 세션 ·
 * 설정은 {@link StateActor#snapshot()} 과 {@code SettingsChanged} 로만 읽는다.
 *
 * <p>WSL 은 포커스 창이 WSL 이고 그 배포판에 실행 중인 세션이 있을 때만, {@value #WSL_GAP_SECONDS}초 이상 간격으로 잰다 — 세션이 없을 때
 * wsl.exe 를 부르면 멈춘 VM 이 깨어난다(FR-MON-02 · NFR-10 · R9). 그 사이 주기는 마지막 결과(값 또는 실패 이유)를 쓴다.
 *
 * <p>상태는 모두 {@link #monitor} 로 지킨다. 구독 · 항목 토글(EDT) · 설정 이벤트(actor 스레드) · 예약(스케줄러 스레드) · 표본(I/O 스레드)이
 * 함께 만진다. 출력 포트의 일정을 조율하는 가변 상태라 규칙 8 의 예외로 application 에 둔다(ADR-0013, {@link HistorySaver} 와 같다).
 */
public final class ResourceWatcher implements WatchResourcesUseCase {
    static final int WSL_GAP_SECONDS = 5;
    /** wsl.exe 호출 사이의 최소 간격(NFR-10). */
    static final Duration WSL_GAP = Duration.ofSeconds(WSL_GAP_SECONDS);
    /** 이 시간 안에 끝나지 않으면 그 항목은 '—'(R9). */
    static final Duration WSL_TIMEOUT = Duration.ofSeconds(1);

    private static final System.Logger LOG = System.getLogger(ResourceWatcher.class.getName());

    private final StateActor state;
    private final ResourceProbe probe;
    private final SystemTools tools;
    private final Scheduler scheduler;
    private final Executor io;
    private final InstantSource clock;
    private final UiDispatcher ui;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Object monitor = new Object();

    // 아래는 monitor 로 지킨다
    private MonitorConfig config;
    private Set<MonitorItem> visible;
    private boolean running;
    // 멈추거나 다시 시작할 때마다 올린다. 이전 세대의 예약 · 표본은 결과를 버리고 다음 주기를 예약하지 않는다
    private long generation;
    private Optional<Cancellable> timer = Optional.empty();
    private CpuWindow window = CpuWindow.empty();
    private Optional<Instant> lastWslAttempt = Optional.empty();
    private Optional<WslResult> lastWsl = Optional.empty();

    /** 판정 전의 표본 하나. */
    private record Measured(ResourceSample sample, Set<MonitorItem> items, Optional<ProbeError> wslUnavailable) {}

    /** 배포판 하나의 마지막 WSL 결과. */
    private record WslResult(String distro, Result<WslUsage, ProbeError> result) {}

    /** 한 주기에 잴 것. monitor 안에서 정하고 밖에서 잰다. */
    private record Plan(
            Instant at,
            Set<MonitorItem> items,
            Optional<WorkspacePath> diskPath,
            Optional<String> wslDistro,
            boolean queryWsl) {}

    /** 만들면서 {@code state} 의 설정 이벤트를 구독한다. 이미 읽은 설정이 있으면 그 값으로, 없으면 기본값으로 시작한다. */
    public ResourceWatcher(
            StateActor state,
            ResourceProbe probe,
            SystemTools tools,
            Scheduler scheduler,
            Executor io,
            InstantSource clock,
            UiDispatcher ui) {
        this.state = Objects.requireNonNull(state, "state");
        this.probe = Objects.requireNonNull(probe, "probe");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.io = Objects.requireNonNull(io, "io");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ui = Objects.requireNonNull(ui, "ui");
        MonitorConfig initial =
                state.snapshot().settings().map(AppConfig::monitor).orElse(MonitorConfig.defaults());
        synchronized (monitor) {
            this.config = initial;
            this.visible = copy(initial.items());
        }
        state.subscribe(this::onEvent);
    }

    @Override
    public Subscription subscribe(Consumer<ResourceReport> listener) {
        Listener registration = new Listener(Objects.requireNonNull(listener, "listener"));
        listeners.add(registration);
        reconcile();
        return () -> {
            listeners.remove(registration);
            reconcile();
        };
    }

    @Override
    public void setItemVisible(MonitorItem item, boolean visible) {
        Objects.requireNonNull(item, "item");
        synchronized (monitor) {
            Set<MonitorItem> next = copy(this.visible);
            if (visible) {
                next.add(item);
            } else {
                next.remove(item);
            }
            this.visible = next;
        }
        reconcile();
    }

    @Override
    public void openTool(MonitorItem item) {
        Objects.requireNonNull(item, "item");
        io.execute(() -> {
            try {
                tools.open(item);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "OS 도구를 열지 못했다: " + item, e);
            }
        });
    }

    // actor 스레드. 설정의 항목이 바뀌었을 때만 상태줄에서 끄고 켠 것을 덮는다 — 다른 설정을 바꿨다고 되살리지 않는다
    private void onEvent(AppEvent event) {
        if (!(event instanceof AppEvent.SettingsChanged(AppConfig next))) {
            return;
        }
        synchronized (monitor) {
            if (!next.monitor().items().equals(config.items())) {
                visible = copy(next.monitor().items());
            }
            config = next.monitor();
        }
        reconcile();
    }

    // 구독 · 설정 · 항목에 맞춰 주기를 시작하거나 멈춘다
    private void reconcile() {
        synchronized (monitor) {
            boolean wanted = !listeners.isEmpty() && config.enabled() && !visible.isEmpty();
            if (wanted == running) {
                return;
            }
            running = wanted;
            generation++;
            timer.ifPresent(Cancellable::cancel);
            timer = Optional.empty();
            if (wanted) {
                long current = generation;
                timer = Optional.of(scheduler.schedule(Duration.ZERO, () -> due(current)));
            }
        }
    }

    // 스케줄러 스레드. 재기는 I/O 로 넘긴다 — 스케줄러 스레드를 막지 않는다
    private void due(long expected) {
        synchronized (monitor) {
            if (!running || expected != generation) {
                return;
            }
            timer = Optional.empty();
        }
        io.execute(() -> sample(expected));
    }

    // I/O 스레드(블로킹). 잰 뒤 같은 세대면 보고하고 다음 주기를 예약한다
    private void sample(long expected) {
        Optional<Plan> plan = plan(expected);
        if (plan.isEmpty()) {
            return;
        }
        Optional<Measured> measured = measure(plan.get());
        Optional<ResourceReport> report;
        synchronized (monitor) {
            if (!running || expected != generation) {
                return;
            }
            report = measured.map(this::judged);
            timer = Optional.of(scheduler.schedule(interval(), () -> due(expected)));
        }
        report.ifPresent(this::deliver);
    }

    private Optional<Plan> plan(long expected) {
        Focus focus = Focus.of(state.snapshot());
        Instant now = clock.instant();
        synchronized (monitor) {
            if (!running || expected != generation) {
                return Optional.empty();
            }
            Set<MonitorItem> items = copy(visible);
            Optional<WorkspacePath> diskPath = items.contains(MonitorItem.DISK) ? focus.cwd() : Optional.empty();
            Optional<String> wslDistro = items.contains(MonitorItem.WSL) ? focus.runningDistro() : Optional.empty();
            boolean queryWsl = wslDistro.isPresent() && wslDue(now);
            if (queryWsl) {
                lastWslAttempt = Optional.of(now);
            }
            return Optional.of(new Plan(now, items, diskPath, wslDistro, queryWsl));
        }
    }

    // monitor 안. 마지막 시도에서 WSL_GAP 이 지났는가(배포판과 상관없이 wsl.exe 호출 전체에 건다)
    private boolean wslDue(Instant now) {
        return lastWslAttempt
                .map(last -> Duration.between(last, now).compareTo(WSL_GAP) >= 0)
                .orElse(true);
    }

    /**
     * 포커스 창에서 읽는 것(순수).
     *
     * @param cwd 포커스 창 셸의 알려진 경로. 세션이 없거나(아직 안 뜸) 홈이면 비어 있다
     * @param runningDistro 포커스 창이 WSL 이고 그 배포판에 Running 세션이 하나라도 있을 때의 배포판 — 포커스 창이 끝났어도 같은 배포판의
     *     다른 창이 돌면 VM 은 깨어 있다
     */
    private record Focus(Optional<WorkspacePath> cwd, Optional<String> runningDistro) {
        static Focus of(AppState snapshot) {
            Map<PaneId, LiveSession> byPane = snapshot.sessions().byPane();
            Optional<LiveSession> focused =
                    Optional.ofNullable(byPane.get(snapshot.workspace().tab().focused()));
            Optional<String> distro =
                    focused.flatMap(live -> live.session().profile().env() instanceof ShellEnvironment.Wsl(String name)
                            ? Optional.of(name)
                            : Optional.empty());
            boolean running = distro.isPresent()
                    && byPane.values().stream()
                            .anyMatch(live -> live.session().state() instanceof SessionState.Running
                                    && live.session().profile().env().equals(new ShellEnvironment.Wsl(distro.get())));
            return new Focus(focused.flatMap(live -> live.session().knownCwd()), distro.filter(_ -> running));
        }
    }

    // monitor 밖(블로킹). 판정은 CPU 창을 바꾸므로 judged 가 monitor 안에서 한다
    private Optional<Measured> measure(Plan plan) {
        HostUsage host;
        try {
            host = probe.host();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "호스트 리소스를 재지 못했다 — 이 주기는 건너뛴다", e);
            return Optional.empty();
        }
        Optional<DiskUsage> disk = plan.diskPath().flatMap(this::disk);
        Optional<Result<WslUsage, ProbeError>> wsl = wsl(plan);
        Optional<WslUsage> wslUsage = wsl.flatMap(Result::value);
        Optional<ProbeError> wslError = wsl.flatMap(Result::error);
        ResourceSample sample = ResourceSample.of(plan.at(), host, disk, wslUsage);
        return Optional.of(new Measured(sample, plan.items(), wslError));
    }

    private Optional<DiskUsage> disk(WorkspacePath cwd) {
        try {
            return probe.disk(cwd);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "디스크를 재지 못했다", e);
            return Optional.empty();
        }
    }

    // 잴 차례면 재서 기억하고, 아니면 같은 배포판의 마지막 결과를 쓴다
    private Optional<Result<WslUsage, ProbeError>> wsl(Plan plan) {
        if (plan.wslDistro().isEmpty()) {
            return Optional.empty();
        }
        String distro = plan.wslDistro().get();
        if (!plan.queryWsl()) {
            synchronized (monitor) {
                return lastWsl.filter(last -> last.distro().equals(distro)).map(WslResult::result);
            }
        }
        Result<WslUsage, ProbeError> result;
        try {
            result = probe.wsl(distro, WSL_TIMEOUT);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "WSL 지표 수집기가 예외를 던졌다", e);
            result = Result.err(new ProbeError.Failed(e.toString()));
        }
        result.error().ifPresent(error -> LOG.log(Level.DEBUG, error.describe()));
        synchronized (monitor) {
            lastWsl = Optional.of(new WslResult(distro, result));
        }
        return Optional.of(result);
    }

    // monitor 안. CPU 창에 이 표본을 넣고 다시 판정한다
    private ResourceReport judged(Measured measured) {
        Duration interval = interval();
        Thresholds thresholds = thresholds();
        window = window.push(measured.sample().cpuLoad(), thresholds.cpuSamplesNeeded(interval));
        ResourceLevels levels = ResourceLevels.of(measured.sample(), window, thresholds, interval);
        return new ResourceReport(measured.sample(), levels, measured.items(), measured.wslUnavailable());
    }

    private void deliver(ResourceReport report) {
        ui.dispatch(() -> {
            for (Listener listener : listeners) {
                try {
                    listener.callback().accept(report);
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "리소스 표시 구독자가 예외를 던졌다", e);
                }
            }
        });
    }

    // monitor 안
    private Duration interval() {
        return Duration.ofSeconds(config.intervalSec());
    }

    // monitor 안
    private Thresholds thresholds() {
        return new Thresholds(config.cpu(), Duration.ofSeconds(config.cpuSustainSec()), config.memory(), config.disk());
    }

    private static Set<MonitorItem> copy(Set<MonitorItem> items) {
        return items.isEmpty() ? EnumSet.noneOf(MonitorItem.class) : EnumSet.copyOf(items);
    }

    // 같은 콜백을 두 번 구독해도 따로 해제되게 등록마다 객체 하나(동일성 비교)
    private static final class Listener {
        private final Consumer<ResourceReport> callback;

        Listener(Consumer<ResourceReport> callback) {
            this.callback = callback;
        }

        Consumer<ResourceReport> callback() {
            return callback;
        }
    }
}
