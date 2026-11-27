package tanager.bootstrap;

import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import tanager.adapter.persistence.JsonConfigRepository;
import tanager.adapter.persistence.JsonStateRepository;
import tanager.adapter.persistence.WatchServiceConfigWatch;
import tanager.application.port.in.ApplyThemeUseCase;
import tanager.application.port.in.ListProfilesUseCase;
import tanager.application.port.in.ManageHistoryUseCase;
import tanager.application.port.in.QueryHistoryTreeUseCase;
import tanager.application.port.in.RecordEnvironmentsUseCase;
import tanager.application.port.in.ResolvedAppearance;
import tanager.application.port.in.SettingsUseCase;
import tanager.application.port.in.StartSessionUseCase;
import tanager.application.port.in.WatchResourcesUseCase;
import tanager.application.port.out.HistoryStoreError;
import tanager.application.port.out.Integration;
import tanager.application.port.out.LoadedHistory;
import tanager.application.port.out.PlatformModule;
import tanager.application.port.out.PtyLauncher;
import tanager.application.port.out.Subscription;
import tanager.application.port.out.UiDispatcher;
import tanager.application.service.AppearanceWatcher;
import tanager.application.service.DefaultProfileMemory;
import tanager.application.service.EnvironmentRecorder;
import tanager.application.service.HistoryManager;
import tanager.application.service.HistorySaver;
import tanager.application.service.HistoryTreeQuery;
import tanager.application.service.IntegrationStatus;
import tanager.application.service.PaneManager;
import tanager.application.service.ProfileCatalog;
import tanager.application.service.ResourceWatcher;
import tanager.application.service.SessionStarter;
import tanager.application.service.SettingsReloader;
import tanager.application.service.VisitRecorder;
import tanager.application.state.AppState;
import tanager.application.state.Command;
import tanager.application.state.HistoryLoadFailed;
import tanager.application.state.HistoryLoaded;
import tanager.application.state.StateActor;
import tanager.domain.history.HistoryPolicy;
import tanager.domain.history.WorkHistory;
import tanager.domain.shared.Result;

/**
 * 조립한 유스케이스들과 앱 생명주기. 포트 구현을 골라 서비스에 넣는 일은 여기서만 한다(ADR-0002). 이력 저장소(state.json)는 프로세스에
 * 하나만 연다 — 잠금이 인스턴스 단위다(ADR-0013).
 */
final class Assembly {
    private static final System.Logger LOG = System.getLogger(Assembly.class.getName());
    private static final String CONFIG_FILE = "config.json";
    private static final String STATE_FILE = "state.json";
    // 종료 때 남은 명령을 처리할 시간과 미저장 이력을 쓸 시간(ADR-0011 · 0012)
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration FLUSH_TIMEOUT = Duration.ofSeconds(2);

    private final ListProfilesUseCase profiles;
    private final StartSessionUseCase sessions;
    private final StateActor state;
    private final ExecutorService stateExecutor;
    private final HistorySaver saver;
    private final ExecutorScheduler scheduler;
    private final JsonStateRepository history;
    private final PaneManager panes;
    private final SettingsReloader settings;
    private final WatchServiceConfigWatch configWatch;
    private final ResourceWatcher resources;
    private final ExecutorScheduler monitorScheduler;
    private final AppearanceWatcher appearance;
    private volatile Subscription watchingSettings = () -> {};

    private Assembly(
            ListProfilesUseCase profiles,
            StartSessionUseCase sessions,
            StateActor state,
            ExecutorService stateExecutor,
            HistorySaver saver,
            ExecutorScheduler scheduler,
            JsonStateRepository history,
            PaneManager panes,
            SettingsReloader settings,
            WatchServiceConfigWatch configWatch,
            ResourceWatcher resources,
            ExecutorScheduler monitorScheduler,
            AppearanceWatcher appearance) {
        this.profiles = profiles;
        this.sessions = sessions;
        this.state = state;
        this.stateExecutor = stateExecutor;
        this.saver = saver;
        this.scheduler = scheduler;
        this.history = history;
        this.panes = panes;
        this.settings = settings;
        this.configWatch = configWatch;
        this.resources = resources;
        this.monitorScheduler = monitorScheduler;
        this.appearance = appearance;
    }

    /**
     * 셸 탐지는 수 초 걸릴 수 있어 창을 띄우는 동안 가상 스레드에서 미리 돌린다 — 첫 {@code start} 는 그 결과를 기다린다(탐지는
     * 한 번뿐이다). 셸 통합 설치 · 이력 불러오기도 같은 방식이다(ADR-0013 · 0014). 불러오기 전 방문은 불러온 이력 위에 합쳐진다.
     *
     * @param ui 리소스 표시(#187) · 테마 변경 알림(#184)을 UI 스레드로 넘긴다
     */
    static Assembly assemble(PlatformModule platform, PtyLauncher launcher, UiDispatcher ui) {
        ProfileCatalog catalog = new ProfileCatalog(platform);
        IntegrationStatus integration = new IntegrationStatus(platform);
        warmShellIntegration(integration);
        Path appData = platform.dirs().appData();
        Path configFile = appData.resolve(CONFIG_FILE);
        JsonConfigRepository config = new JsonConfigRepository(configFile);
        JsonStateRepository history =
                new JsonStateRepository(appData.resolve(STATE_FILE), HistoryPolicy.DEFAULT, InstantSource.system());
        ExecutorService stateExecutor = StateActor.serialExecutor();
        StateActor state = new StateActor(AppState.initial(WorkHistory.empty(HistoryPolicy.DEFAULT)), stateExecutor);
        ExecutorScheduler scheduler = new ExecutorScheduler("tanager-history-timer");
        Executor io = task -> Thread.ofVirtual().name("tanager-history-save").start(task);
        // 불러오기 명령보다 먼저 구독해야 불러온 이력을 "저장할 것 없음"으로 안다
        HistorySaver saver = new HistorySaver(state, history, scheduler, io);
        loadHistory(history, state);
        detectShells(catalog, new EnvironmentRecorder(state));
        VisitRecorder visits = new VisitRecorder(state, InstantSource.system());
        SessionStarter starter = new SessionStarter(
                catalog, config, platform, integration, launcher, new DefaultProfileMemory(config), state, visits);
        PaneManager panes = new PaneManager(catalog, config, platform.paths(), state);
        SettingsReloader settings = new SettingsReloader(config, state);
        // 설정을 읽기 전에 구독해야 시작 때의 SettingsChanged 를 받는다. 예약은 전용 타이머, 재기는 주기마다 가상 스레드(SDD §6.6)
        ExecutorScheduler monitorScheduler = new ExecutorScheduler("tanager-monitor-timer");
        ResourceWatcher resources = new ResourceWatcher(
                state,
                platform.resources(),
                platform.systemTools(),
                monitorScheduler,
                task -> Thread.ofVirtual().name("tanager-monitor").start(task),
                InstantSource.system(),
                ui);
        // 설정을 읽기 전에 구독해야 시작 때의 SettingsChanged 를 받는다. 주기 예약은 저장 타이머를 같이 쓴다(일이 짧다)
        AppearanceWatcher appearance = new AppearanceWatcher(
                state,
                platform,
                scheduler,
                task -> Thread.ofVirtual().name("tanager-appearance").start(task),
                ui);
        return new Assembly(
                catalog,
                starter,
                state,
                stateExecutor,
                saver,
                scheduler,
                history,
                panes,
                settings,
                new WatchServiceConfigWatch(configFile),
                resources,
                monitorScheduler,
                appearance);
    }

    // 탐지가 끝나면 그 순서를 트리의 환경 순서로 알린다(#83). 실패하면 트리는 이력의 환경을 이름순으로 놓는다
    private static void detectShells(ProfileCatalog catalog, RecordEnvironmentsUseCase environments) {
        Thread.ofVirtual().name("tanager-shell-detect").start(() -> {
            try {
                environments.profilesDetected(catalog.list());
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "셸 탐지 실패", e);
            }
        });
    }

    ListProfilesUseCase profiles() {
        return profiles;
    }

    StartSessionUseCase sessions() {
        return sessions;
    }

    /** 사이드바가 그릴 이력 트리(#84). */
    QueryHistoryTreeUseCase historyTree() {
        return new HistoryTreeQuery(state);
    }

    /** 사이드바 메뉴의 고정 · 별칭 · 숨김 · 기록 삭제(#86). */
    ManageHistoryUseCase historyManager() {
        return new HistoryManager(state);
    }

    StateActor state() {
        return state;
    }

    /** 지금 설정과 설정 파일(FR-CFG-03). */
    SettingsUseCase settings() {
        return settings;
    }

    /**
     * 설정 파일 감시를 걸고 한 번 읽는다 — {@code SettingsChanged} 가 한 번 나간다(ADR-0009 개정). UI 가 이벤트를 구독한 뒤에 부른다. 그 전에
     * 부르면 시작 때의 이벤트를 UI 가 받지 못한다. 한 번만 부른다.
     */
    void startSettings() {
        watchingSettings = settings.start(configWatch);
    }

    /** 상태줄의 리소스 표시(#187). 표시는 상태줄 이슈(#188)가 한다. */
    WatchResourcesUseCase resources() {
        return resources;
    }

    /** 지금 쓸 테마 · 움직임과 그 변경(FR-UI-01, #184). 화면에 적용하는 것은 #185. */
    ApplyThemeUseCase appearance() {
        return appearance;
    }

    /** OS 외관을 부른 스레드에서 한 번 읽는다(Windows: reg.exe). 창을 띄우기 전에 EDT 가 아닌 곳에서 부른다. */
    ResolvedAppearance readAppearance() {
        return appearance.readNow();
    }

    /** 창 열기 · 닫기 · 포커스(#88 · #89). */
    PaneManager panes() {
        return panes;
    }

    /**
     * 앱을 끝낼 때 한 번 부른다(ADR-0011 · 0012): 설정 감시를 끊고 → 상태 실행기를 닫고 남은 명령을 처리한 뒤 → 미저장 이력을 바로 저장(최대 2초) → 저장소를
     * 닫는다(잠금을 푼다). 저장하지 못했으면 저장소를 닫지 않는다. 이 뒤의 명령은 실패한 응답을 받는다.
     */
    void shutdown() {
        watchingSettings.cancel();
        // 리소스 수집 예약을 먼저 버린다 — 종료 중에 wsl.exe 를 띄우지 않게
        monitorScheduler.close();
        shutdownInOrder(stateExecutor, saver, FLUSH_TIMEOUT, scheduler::close, history::close);
    }

    /**
     * {@link #shutdown} 의 순서. flush 가 실패하면(막힌 저장이 시간을 넘김 · 저장 실패) 저장소를 닫지 않는다 — 닫기는 진행 중인 저장과 같은
     * 잠금을 기다려 종료를 붙잡을 수 있다. 프로세스가 끝나면 OS 가 파일 잠금을 푼다(ADR-0011 개정).
     */
    static void shutdownInOrder(
            ExecutorService stateExecutor,
            HistorySaver saver,
            Duration flushTimeout,
            Runnable stopTimers,
            Runnable closeStore) {
        stateExecutor.shutdown();
        try {
            if (!stateExecutor.awaitTermination(DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                LOG.log(System.Logger.Level.WARNING, "상태 명령이 {0} 안에 끝나지 않았다", DRAIN_TIMEOUT);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        boolean flushed = saver.flush(flushTimeout);
        stopTimers.run();
        if (flushed) {
            closeStore.run();
        } else {
            LOG.log(System.Logger.Level.WARNING, "종료 때 이력을 저장하지 못해 저장소를 닫지 않고 끝낸다(잠금은 프로세스가 끝나면 풀린다)");
        }
    }

    // 블로킹 I/O 라 가상 스레드에서. 종료가 먼저 와 결과를 넘기지 못하면 다시 잡은 잠금을 푼다
    private static void loadHistory(JsonStateRepository history, StateActor state) {
        Thread.ofVirtual().name("tanager-history-load").start(() -> {
            Command<Boolean> loaded;
            try {
                loaded = switch (history.load()) {
                    case Result.Ok<LoadedHistory, HistoryStoreError>(LoadedHistory result) -> new HistoryLoaded(result);
                    case Result.Err<LoadedHistory, HistoryStoreError>(HistoryStoreError error) ->
                        new HistoryLoadFailed(error);
                };
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "이력 불러오기 실패", e);
                loaded = new HistoryLoadFailed(new HistoryStoreError.Io(e.toString()));
            }
            state.submit(loaded).whenComplete((reply, failure) -> {
                if (rejected(failure)) {
                    history.close();
                }
            });
        });
    }

    private static boolean rejected(Throwable failure) {
        Throwable cause = failure instanceof CompletionException wrapped ? wrapped.getCause() : failure;
        return cause instanceof RejectedExecutionException;
    }

    /** 파일 I/O 라 백그라운드에서 한 번 데운다. 셸에 주입하는 일은 #55 · #81. */
    private static void warmShellIntegration(IntegrationStatus integration) {
        Thread.ofVirtual().name("tanager-shell-integration").start(() -> {
            try {
                switch (integration.await()) {
                    case Integration.Installed(var directory) ->
                        LOG.log(System.Logger.Level.INFO, "셸 통합 스크립트: {0}", directory);
                    case Integration.Unavailable(var problem) ->
                        LOG.log(System.Logger.Level.WARNING, "셸 통합 없음: {0}", problem.describe());
                }
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "셸 통합 설치 실패", e);
            }
        });
    }
}
