package tanager.bootstrap;

import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import tanager.adapter.persistence.JsonConfigRepository;
import tanager.adapter.persistence.JsonStateRepository;
import tanager.adapter.persistence.WatchServiceConfigWatch;
import tanager.application.port.in.ApplyThemeUseCase;
import tanager.application.port.in.CheckMissingPathsUseCase;
import tanager.application.port.in.ImportConversationsUseCase;
import tanager.application.port.in.ListProfilesUseCase;
import tanager.application.port.in.ManageHistoryUseCase;
import tanager.application.port.in.QueryHistoryTreeUseCase;
import tanager.application.port.in.QueryToolkitUseCase;
import tanager.application.port.in.RecordEnvironmentsUseCase;
import tanager.application.port.in.RecordWindowStateUseCase;
import tanager.application.port.in.ResolvedAppearance;
import tanager.application.port.in.SettingsUseCase;
import tanager.application.port.in.StartSessionUseCase;
import tanager.application.port.in.WatchResourcesUseCase;
import tanager.application.port.out.ConversationSource;
import tanager.application.port.out.HistoryStoreError;
import tanager.application.port.out.Integration;
import tanager.application.port.out.LoadedHistory;
import tanager.application.port.out.LoadedWorkspace;
import tanager.application.port.out.PlatformModule;
import tanager.application.port.out.PtyLauncher;
import tanager.application.port.out.Subscription;
import tanager.application.port.out.ToolkitProvider;
import tanager.application.port.out.UiDispatcher;
import tanager.application.port.out.WorkspaceLoadNotice;
import tanager.application.service.AppearanceWatcher;
import tanager.application.service.ConversationImporter;
import tanager.application.service.DefaultProfileMemory;
import tanager.application.service.EnvironmentRecorder;
import tanager.application.service.HistoryManager;
import tanager.application.service.HistorySaver;
import tanager.application.service.HistoryTreeQuery;
import tanager.application.service.IntegrationStatus;
import tanager.application.service.MissingPathScanner;
import tanager.application.service.PaneManager;
import tanager.application.service.ProfileCatalog;
import tanager.application.service.ResourceWatcher;
import tanager.application.service.SessionStarter;
import tanager.application.service.SettingsReloader;
import tanager.application.service.ToolkitQueryService;
import tanager.application.service.VisitRecorder;
import tanager.application.service.WindowRecorder;
import tanager.application.service.WorkspaceRestorer;
import tanager.application.service.WorkspaceSaver;
import tanager.application.state.AppState;
import tanager.application.state.Command;
import tanager.application.state.HistoryLoadFailed;
import tanager.application.state.HistoryLoaded;
import tanager.application.state.StateActor;
import tanager.application.state.WorkspaceLoaded;
import tanager.domain.history.HistoryPolicy;
import tanager.domain.history.WorkHistory;
import tanager.domain.shared.Result;
import tanager.domain.workspace.Workspace;
import tanager.domain.workspace.WorkspaceSnapshot;

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
    private static final int MAX_PROVIDER_FAILURES = 16;

    private final ListProfilesUseCase profiles;
    private final StartSessionUseCase sessions;
    private final StateActor state;
    private final ExecutorService stateExecutor;
    private final HistorySaver saver;
    private final WorkspaceSaver workspaceSaver;
    private final StoredState stored;
    private final ExecutorScheduler scheduler;
    private final JsonStateRepository history;
    private final PaneManager panes;
    private final SettingsReloader settings;
    private final WatchServiceConfigWatch configWatch;
    private final ResourceWatcher resources;
    private final ExecutorScheduler monitorScheduler;
    private final AppearanceWatcher appearance;
    private final MissingPathScanner missingPaths;
    private final ToolkitQueryService toolkit;
    private final ConversationImporter conversations;
    private volatile Subscription watchingSettings = () -> {};

    private Assembly(
            ListProfilesUseCase profiles,
            StartSessionUseCase sessions,
            StateActor state,
            ExecutorService stateExecutor,
            HistorySaver saver,
            WorkspaceSaver workspaceSaver,
            StoredState stored,
            ExecutorScheduler scheduler,
            JsonStateRepository history,
            PaneManager panes,
            SettingsReloader settings,
            WatchServiceConfigWatch configWatch,
            ResourceWatcher resources,
            ExecutorScheduler monitorScheduler,
            AppearanceWatcher appearance,
            MissingPathScanner missingPaths,
            ToolkitQueryService toolkit,
            ConversationImporter conversations) {
        this.profiles = profiles;
        this.sessions = sessions;
        this.state = state;
        this.stateExecutor = stateExecutor;
        this.saver = saver;
        this.workspaceSaver = workspaceSaver;
        this.stored = stored;
        this.scheduler = scheduler;
        this.history = history;
        this.panes = panes;
        this.settings = settings;
        this.configWatch = configWatch;
        this.resources = resources;
        this.monitorScheduler = monitorScheduler;
        this.appearance = appearance;
        this.missingPaths = missingPaths;
        this.toolkit = toolkit;
        this.conversations = conversations;
    }

    /**
     * 셸 탐지는 수 초 걸릴 수 있어 창을 띄우는 동안 가상 스레드에서 미리 돌린다 — 첫 {@code start} 는 그 결과를 기다린다(탐지는
     * 한 번뿐이다). 셸 통합 설치도 같은 방식이다(ADR-0014).
     *
     * <p>state.json 은 <b>창을 만들기 전에</b> 읽는다(ADR-0013 개정 #179) — 가상 스레드에서 셸 탐지와 나란히 읽기 시작하고, 상태를 만들기
     * 직전에 기다린다(로컬 파일이라 수 ms). 복원할 배치를 모르고 p1 셸을 먼저 띄우면 그 셸을 버려야 하기 때문이다. 처음 작업 공간은
     * 복원 계획이고, 불러온 이력 · 알림은 UI 가 구독한 뒤 {@link #startStore} 가 넣는다.
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
        CompletableFuture<StoredState> reading = readState(history);
        warmShells(catalog);
        StoredState stored = reading.join();
        WorkspaceRestorer restorer = new WorkspaceRestorer();
        Workspace planned = restorer.plan(stored.snapshot());
        ExecutorService stateExecutor = StateActor.serialExecutor();
        // 창 상태는 작업 공간 저장기가 불러온 값과 비교하므로 처음 상태에 넣어 둔다(#180)
        StateActor state = new StateActor(
                AppState.initial(WorkHistory.empty(HistoryPolicy.DEFAULT), planned)
                        .withWindow(restorer.window(stored.snapshot())),
                stateExecutor);
        ExecutorScheduler scheduler = new ExecutorScheduler("tanager-save-timer");
        // 불러오기 명령보다 먼저 구독해야 불러온 이력 · 작업 공간을 "저장할 것 없음"으로 안다
        HistorySaver saver = new HistorySaver(
                state,
                history,
                scheduler,
                task -> Thread.ofVirtual().name("tanager-history-save").start(task));
        WorkspaceSaver workspaceSaver = new WorkspaceSaver(
                state,
                history,
                scheduler,
                task -> Thread.ofVirtual().name("tanager-workspace-save").start(task),
                stored.snapshot());
        // 불러온 이력의 HistoryChanged 로 시작 뒤 한 번 확인한다 — 불러오기보다 먼저 구독한다(#186)
        MissingPathScanner missingPaths = new MissingPathScanner(
                state,
                platform.directories(),
                task -> Thread.ofVirtual().name("tanager-path-check").start(task),
                InstantSource.system());
        // 셸 탐지 · 설정 · 불러오기보다 먼저 구독해야 첫 읽기 계기를 놓치지 않는다. 읽기는 가상 스레드(블로킹 파일 I/O, #215)
        ConversationImporter conversations = new ConversationImporter(
                state,
                loadAll(ConversationSource.class, "대화 공급자"),
                platform.hostFiles(),
                task -> Thread.ofVirtual().name("tanager-conversations").start(task),
                InstantSource.system());
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
        // 도구함은 상태가 아니다 — 액터를 거치지 않고 가상 스레드에서 읽는다. 시간 제한 예약은 저장 타이머를 같이 쓴다(일이 짧다)
        ToolkitQueryService toolkit = new ToolkitQueryService(
                loadAll(ToolkitProvider.class, "도구함 공급자"),
                platform.hostFiles(),
                () -> settings.current().toolkit(),
                task -> Thread.ofVirtual().name("tanager-toolkit").start(task),
                scheduler);
        return new Assembly(
                catalog,
                starter,
                state,
                stateExecutor,
                saver,
                workspaceSaver,
                stored,
                scheduler,
                history,
                panes,
                settings,
                new WatchServiceConfigWatch(configFile),
                resources,
                monitorScheduler,
                appearance,
                missingPaths,
                toolkit,
                conversations);
    }

    // 등록된 공급자 모두(도구함 ADR-0019 · 대화 ADR-0020). 만들지 못한 공급자는 빼고 로그만 남긴다 — 공급자 하나 때문에 앱이 뜨지 않으면 안 된다
    private static <T> List<T> loadAll(Class<T> type, String what) {
        List<T> found = new ArrayList<>();
        var providers = ServiceLoader.load(type).iterator();
        // 잘못된 등록이 같은 오류를 되풀이해도 끝나게 횟수를 둔다
        for (int failures = 0; failures < MAX_PROVIDER_FAILURES; ) {
            try {
                if (!providers.hasNext()) {
                    break;
                }
                found.add(providers.next());
            } catch (ServiceConfigurationError e) {
                failures++;
                LOG.log(System.Logger.Level.ERROR, what + "를 만들지 못해 뺀다", e);
            }
        }
        LOG.log(System.Logger.Level.INFO, "{0} {1}개", what, found.size());
        return List.copyOf(found);
    }

    // 상태를 만들기 전에 시작한다 — state.json 을 기다리는 동안에도 탐지가 돈다(탐지는 한 번뿐이고 뒤의 호출은 그 결과를 기다린다)
    private static void warmShells(ProfileCatalog catalog) {
        Thread.ofVirtual().name("tanager-shell-warm").start(() -> {
            try {
                catalog.list();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "셸 탐지 실패", e);
            }
        });
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

    /** 사라진 이력 경로 확인 — 창 활성화 때 다시 확인한다(#186). */
    CheckMissingPathsUseCase missingPaths() {
        return missingPaths;
    }

    /** 고른 폴더 · 세션의 도구함(FR-TK-01, #209). 패널은 후속 UI 이슈. */
    QueryToolkitUseCase toolkit() {
        return toolkit;
    }

    /** 이전 대화 가져오기 — 창 활성화 때 오래된 기록을 다시 읽는다(FR-TK-09, #215). */
    ImportConversationsUseCase conversations() {
        return conversations;
    }

    /** 메인 창의 자리 · 크기 · 최대화 · 사이드바 기록(FR-RES-03, #180). 저장은 작업 공간 저장기가 한다. */
    RecordWindowStateUseCase windowState() {
        return new WindowRecorder(state);
    }

    /** 창 열기 · 닫기 · 포커스(#88 · #89). */
    PaneManager panes() {
        return panes;
    }

    /**
     * 불러온 이력(또는 불러오기 실패)과 작업 공간 불러오기 알림을 상태에 넣는다(#179). UI 가 이벤트를 구독한 뒤에 한 번 부른다 — 그 전에
     * 부르면 불러오기 알림을 UI 가 받지 못한다. 그때까지 저장소는 불러오는 중(Loading)이라 방문은 쌓였다가 불러온 이력 위에 합쳐진다.
     */
    void startStore() {
        // 종료가 먼저 와 결과를 넘기지 못하면 잡은 잠금을 푼다
        state.submit(stored.history()).whenComplete((reply, failure) -> {
            if (rejected(failure)) {
                history.close();
            }
        });
        state.submit(new WorkspaceLoaded(stored.notices()));
    }

    /**
     * 앱을 끝낼 때 한 번 부른다(ADR-0011 · 0012): 설정 감시를 끊고 → 상태 실행기를 닫고 남은 명령을 처리한 뒤 → 미저장 이력 · 작업 공간을 바로 저장(합쳐
     * 최대 2초) → 저장소를 닫는다(잠금을 푼다). 저장하지 못했으면 저장소를 닫지 않는다. 이 뒤의 명령은 실패한 응답을 받는다.
     */
    void shutdown() {
        watchingSettings.cancel();
        // 리소스 수집 예약을 먼저 버린다 — 종료 중에 wsl.exe 를 띄우지 않게
        monitorScheduler.close();
        shutdownInOrder(
                stateExecutor,
                List.of(saver::flush, workspaceSaver::flush),
                FLUSH_TIMEOUT,
                scheduler::close,
                history::close);
    }

    /** 종료 때 미저장 변경을 바로 쓰는 일 하나({@link HistorySaver#flush} · {@link WorkspaceSaver#flush}). */
    @FunctionalInterface
    interface Flush {
        /** 저장할 것이 없었거나 저장했으면(쓸 수 없는 상태라 건너뛴 것 포함) {@code true}. */
        boolean flush(Duration timeout);
    }

    /**
     * {@link #shutdown} 의 순서. {@code flushes} 는 순서대로 하나의 기한({@code flushTimeout}) 안에서 돈다. 하나라도 실패하면(막힌 저장이 시간을
     * 넘김 · 저장 실패) 저장소를 닫지 않는다 — 닫기는 진행 중인 저장과 같은 잠금을 기다려 종료를 붙잡을 수 있다. 프로세스가 끝나면 OS 가 파일
     * 잠금을 푼다(ADR-0011 개정).
     */
    static void shutdownInOrder(
            ExecutorService stateExecutor,
            List<Flush> flushes,
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
        long deadline = System.nanoTime() + flushTimeout.toNanos();
        boolean flushed = true;
        for (Flush flush : flushes) {
            Duration left = Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
            flushed &= flush.flush(left);
        }
        stopTimers.run();
        if (flushed) {
            closeStore.run();
        } else {
            LOG.log(System.Logger.Level.WARNING, "종료 때 이력 · 작업 공간을 저장하지 못해 저장소를 닫지 않고 끝낸다(잠금은 프로세스가 끝나면 풀린다)");
        }
    }

    /** 시작 때 state.json 에서 읽은 것. 이력 쪽은 상태에 넣을 명령(불러옴 · 실패)이다. */
    private record StoredState(
            Command<Boolean> history, Optional<WorkspaceSnapshot> snapshot, List<WorkspaceLoadNotice> notices) {}

    // 블로킹 I/O 라 가상 스레드에서. 문서는 한 번만 읽는다 — 작업 공간 · 이력 두 부분을 차례로 꺼낸다(ADR-0013 개정 #178)
    private static CompletableFuture<StoredState> readState(JsonStateRepository store) {
        CompletableFuture<StoredState> read = new CompletableFuture<>();
        Thread.ofVirtual().name("tanager-state-load").start(() -> {
            try {
                read.complete(readStateNow(store));
            } catch (Throwable e) {
                read.completeExceptionally(e);
            }
        });
        return read;
    }

    private static StoredState readStateNow(JsonStateRepository store) {
        Optional<WorkspaceSnapshot> snapshot = Optional.empty();
        List<WorkspaceLoadNotice> notices = List.of();
        try {
            // 실패는 이력 쪽 load 가 같은 오류로 알린다 — 여기서는 처음 작업 공간으로 시작할 뿐이다
            Optional<LoadedWorkspace> workspace = store.loadWorkspace().value();
            snapshot = workspace.flatMap(LoadedWorkspace::snapshot);
            notices = workspace.map(LoadedWorkspace::notices).orElse(List.of());
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "작업 공간 불러오기 실패", e);
        }
        Command<Boolean> history;
        try {
            history = switch (store.load()) {
                case Result.Ok<LoadedHistory, HistoryStoreError>(LoadedHistory result) -> new HistoryLoaded(result);
                case Result.Err<LoadedHistory, HistoryStoreError>(HistoryStoreError error) ->
                    new HistoryLoadFailed(error);
            };
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "이력 불러오기 실패", e);
            history = new HistoryLoadFailed(new HistoryStoreError.Io(e.toString()));
        }
        return new StoredState(history, snapshot, notices);
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
