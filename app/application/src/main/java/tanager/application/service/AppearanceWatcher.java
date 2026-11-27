package tanager.application.service;

import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import tanager.application.port.in.AppEvent;
import tanager.application.port.in.ApplyThemeUseCase;
import tanager.application.port.in.ResolvedAppearance;
import tanager.application.port.out.AppConfig;
import tanager.application.port.out.AppearanceConfig;
import tanager.application.port.out.Cancellable;
import tanager.application.port.out.PlatformModule;
import tanager.application.port.out.Scheduler;
import tanager.application.port.out.Subscription;
import tanager.application.port.out.SystemAppearance;
import tanager.application.port.out.UiDispatcher;
import tanager.application.state.StateActor;

/**
 * OS 외관을 다시 읽어 설정과 합치고, 값이 바뀔 때만 알린다(FR-UI-01, ADR-0018). 다시 읽는 때는 둘이다 — 구독이 있는 동안 {@link #INTERVAL}
 * 마다, 그리고 UI 가 창 활성화 때 {@link #refresh()} 를 부를 때. 읽기는 I/O 실행기에서 한 번에 하나만 돌고, 도중에 온 요청은 끝난 뒤 한 번으로
 * 모은다. 설정은 {@code SettingsChanged} 로만 받는다. 결과는 저장 상태가 아니라 StateActor 를 거치지 않는다(SDD §5.7).
 *
 * <p>상태는 모두 {@link #monitor} 로 지킨다. 구독(EDT) · 설정 이벤트(actor 스레드) · 예약(스케줄러 스레드) · 읽기(I/O 스레드)가 함께 만진다.
 * 출력 포트의 일정을 조율하는 가변 상태라 규칙 8 의 예외로 application 에 둔다(ADR-0013 · 0018, {@link HistorySaver} 와 같다).
 */
public final class AppearanceWatcher implements ApplyThemeUseCase {
    /** 주기 읽기 간격. {@code reg.exe} 두 번은 수십 ms 라 부하가 작다(ADR-0018). */
    static final Duration INTERVAL = Duration.ofSeconds(5);

    private static final System.Logger LOG = System.getLogger(AppearanceWatcher.class.getName());

    /** 읽기 진행 상태. 동시에 하나만 돌리고 도중의 요청은 한 번으로 모은다. */
    private enum Reading {
        IDLE,
        /** I/O 실행기에 넘겼고 아직 시작하지 않았다 — 새 요청은 이 읽기가 맡는다. */
        QUEUED,
        RUNNING,
        /** 읽는 도중 요청이 왔다 — 끝나면 한 번 더 읽는다. */
        RUNNING_AGAIN
    }

    private final PlatformModule platform;
    private final Scheduler scheduler;
    private final Executor io;
    private final UiDispatcher ui;
    private final Object monitor = new Object();

    // 아래는 monitor 로 지킨다
    private AppearanceConfig settings;
    private SystemAppearance os = SystemAppearance.DEFAULT;
    private ResolvedAppearance published;
    private final List<Listener> listeners = new ArrayList<>();
    private Optional<Cancellable> timer = Optional.empty();
    // 주기를 멈추거나 다시 시작할 때마다 올린다. 이전 세대의 예약은 돌아도 아무것도 하지 않는다
    private long generation;
    private Reading reading = Reading.IDLE;

    /**
     * 만들면서 {@code state} 의 설정 이벤트를 구독한다. 이미 읽은 설정이 있으면 그 값으로, 없으면 기본값으로 시작한다. OS 는 아직 읽지 않는다 —
     * 시작 때 {@link #readNow()} 를 부른다.
     *
     * @param io 블로킹 읽기({@code platform.appearance()})를 돌릴 실행기. 운영은 가상 스레드
     * @param ui 알림을 UI 스레드로 넘긴다
     */
    public AppearanceWatcher(
            StateActor state, PlatformModule platform, Scheduler scheduler, Executor io, UiDispatcher ui) {
        Objects.requireNonNull(state, "state");
        this.platform = Objects.requireNonNull(platform, "platform");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.io = Objects.requireNonNull(io, "io");
        this.ui = Objects.requireNonNull(ui, "ui");
        AppearanceConfig initial =
                state.snapshot().settings().map(AppConfig::appearance).orElse(AppearanceConfig.defaults());
        synchronized (monitor) {
            this.settings = initial;
            this.published = ThemeResolver.resolve(initial, os);
        }
        state.subscribe(this::onEvent);
    }

    /**
     * 부른 스레드에서 OS 를 한 번 읽어 반영하고 지금 값을 돌려준다. 외부 명령을 실행할 수 있어 EDT 에서 부르지 않는다 — 시작 때 창을 띄우기 전에
     * 부른다. 이미 다른 읽기가 진행 중이면 기다리지 않고 그 뒤 한 번 더 읽게 하고 지금 값을 돌려준다.
     */
    public ResolvedAppearance readNow() {
        synchronized (monitor) {
            if (reading != Reading.IDLE) {
                if (reading == Reading.RUNNING) {
                    reading = Reading.RUNNING_AGAIN;
                }
                return published;
            }
            reading = Reading.RUNNING;
        }
        read();
        return current();
    }

    @Override
    public ResolvedAppearance current() {
        synchronized (monitor) {
            return published;
        }
    }

    @Override
    public Subscription subscribe(Consumer<ResolvedAppearance> listener) {
        Listener registration = new Listener(Objects.requireNonNull(listener, "listener"));
        synchronized (monitor) {
            listeners.add(registration);
            if (listeners.size() == 1) {
                startPolling();
            }
        }
        return () -> {
            registration.cancelled = true;
            synchronized (monitor) {
                if (listeners.remove(registration) && listeners.isEmpty()) {
                    stopPolling();
                }
            }
        };
    }

    @Override
    public void refresh() {
        boolean submit = false;
        synchronized (monitor) {
            switch (reading) {
                case IDLE -> {
                    reading = Reading.QUEUED;
                    submit = true;
                }
                case RUNNING -> reading = Reading.RUNNING_AGAIN;
                case QUEUED, RUNNING_AGAIN -> {
                    // 앞으로 돌 읽기가 이 요청을 맡는다
                }
            }
        }
        if (submit) {
            io.execute(this::queuedRead);
        }
    }

    // I/O 스레드
    private void queuedRead() {
        synchronized (monitor) {
            reading = Reading.RUNNING;
        }
        read();
    }

    // reading == RUNNING 인 스레드 하나만 들어온다. 블로킹 읽기는 monitor 밖에서
    private void read() {
        Optional<SystemAppearance> next;
        try {
            next = Optional.of(platform.appearance());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "OS 외관을 읽지 못해 마지막 값을 그대로 쓴다", e);
            next = Optional.empty();
        }
        boolean again;
        synchronized (monitor) {
            next.ifPresent(value -> {
                os = value;
                publishIfChanged();
            });
            again = reading == Reading.RUNNING_AGAIN;
            reading = again ? Reading.QUEUED : Reading.IDLE;
        }
        if (again) {
            io.execute(this::queuedRead);
        }
    }

    // actor 스레드
    private void onEvent(AppEvent event) {
        if (!(event instanceof AppEvent.SettingsChanged(AppConfig config))) {
            return;
        }
        synchronized (monitor) {
            settings = config.appearance();
            publishIfChanged();
        }
    }

    // monitor 안. 넘기는 순서가 바뀐 순서다 — UiDispatcher 는 막지 않고 넘긴 순서대로 돌린다
    private void publishIfChanged() {
        ResolvedAppearance next = ThemeResolver.resolve(settings, os);
        if (next.equals(published)) {
            return;
        }
        published = next;
        for (Listener listener : List.copyOf(listeners)) {
            ui.dispatch(() -> deliver(listener, next));
        }
    }

    // UI 스레드. 넘긴 뒤 끊은 구독에는 보내지 않는다
    private static void deliver(Listener listener, ResolvedAppearance value) {
        if (listener.cancelled) {
            return;
        }
        try {
            listener.callback.accept(value);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "테마 구독자가 예외를 던졌다", e);
        }
    }

    // monitor 안
    private void startPolling() {
        generation++;
        long current = generation;
        timer = Optional.of(scheduler.schedule(INTERVAL, () -> due(current)));
    }

    // monitor 안
    private void stopPolling() {
        generation++;
        timer.ifPresent(Cancellable::cancel);
        timer = Optional.empty();
    }

    // 스케줄러 스레드. 다음 주기를 먼저 걸고 읽기는 I/O 로 넘긴다 — 읽기가 겹치지 않는 것은 refresh 가 맡는다
    private void due(long expected) {
        synchronized (monitor) {
            if (expected != generation) {
                return;
            }
            timer = Optional.of(scheduler.schedule(INTERVAL, () -> due(expected)));
        }
        refresh();
    }

    // 같은 콜백을 두 번 구독해도 따로 해제되게 등록마다 객체 하나(동일성 비교)
    private static final class Listener {
        private final Consumer<ResolvedAppearance> callback;
        private volatile boolean cancelled;

        Listener(Consumer<ResolvedAppearance> callback) {
            this.callback = callback;
        }
    }
}
