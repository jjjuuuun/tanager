package dev.tanager.bootstrap;

import dev.tanager.adapter.persistence.JsonConfigRepository;
import dev.tanager.adapter.persistence.JsonStateRepository;
import dev.tanager.application.port.in.ListProfilesUseCase;
import dev.tanager.application.port.in.StartSessionUseCase;
import dev.tanager.application.port.out.HistoryStoreError;
import dev.tanager.application.port.out.Integration;
import dev.tanager.application.port.out.LoadedHistory;
import dev.tanager.application.port.out.PlatformModule;
import dev.tanager.application.port.out.PtyLauncher;
import dev.tanager.application.service.DefaultProfileMemory;
import dev.tanager.application.service.HistorySaver;
import dev.tanager.application.service.IntegrationStatus;
import dev.tanager.application.service.ProfileCatalog;
import dev.tanager.application.service.SessionStarter;
import dev.tanager.application.service.VisitRecorder;
import dev.tanager.application.state.AppState;
import dev.tanager.application.state.Command;
import dev.tanager.application.state.HistoryLoadFailed;
import dev.tanager.application.state.HistoryLoaded;
import dev.tanager.application.state.StateActor;
import dev.tanager.domain.history.HistoryPolicy;
import dev.tanager.domain.history.WorkHistory;
import dev.tanager.domain.shared.Result;
import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

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

    private Assembly(
            ListProfilesUseCase profiles,
            StartSessionUseCase sessions,
            StateActor state,
            ExecutorService stateExecutor,
            HistorySaver saver,
            ExecutorScheduler scheduler,
            JsonStateRepository history) {
        this.profiles = profiles;
        this.sessions = sessions;
        this.state = state;
        this.stateExecutor = stateExecutor;
        this.saver = saver;
        this.scheduler = scheduler;
        this.history = history;
    }

    /**
     * 셸 탐지는 수 초 걸릴 수 있어 창을 띄우는 동안 가상 스레드에서 미리 돌린다 — 첫 {@code start} 는 그 결과를 기다린다(탐지는
     * 한 번뿐이다). 셸 통합 설치 · 이력 불러오기도 같은 방식이다(ADR-0013 · 0014). 불러오기 전 방문은 불러온 이력 위에 합쳐진다.
     */
    static Assembly assemble(PlatformModule platform, PtyLauncher launcher) {
        ProfileCatalog catalog = new ProfileCatalog(platform);
        Thread.ofVirtual().name("tanager-shell-detect").start(() -> {
            try {
                catalog.list();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "셸 탐지 실패", e);
            }
        });
        IntegrationStatus integration = new IntegrationStatus(platform);
        warmShellIntegration(integration);
        Path appData = platform.dirs().appData();
        JsonConfigRepository config = new JsonConfigRepository(appData.resolve(CONFIG_FILE));
        JsonStateRepository history =
                new JsonStateRepository(appData.resolve(STATE_FILE), HistoryPolicy.DEFAULT, InstantSource.system());
        ExecutorService stateExecutor = StateActor.serialExecutor();
        StateActor state = new StateActor(AppState.initial(WorkHistory.empty(HistoryPolicy.DEFAULT)), stateExecutor);
        ExecutorScheduler scheduler = new ExecutorScheduler("tanager-history-timer");
        Executor io = task -> Thread.ofVirtual().name("tanager-history-save").start(task);
        // 불러오기 명령보다 먼저 구독해야 불러온 이력을 "저장할 것 없음"으로 안다
        HistorySaver saver = new HistorySaver(state, history, scheduler, io);
        loadHistory(history, state);
        VisitRecorder visits = new VisitRecorder(state, InstantSource.system());
        SessionStarter starter = new SessionStarter(
                catalog, config, platform, integration, launcher, new DefaultProfileMemory(config), state, visits);
        return new Assembly(catalog, starter, state, stateExecutor, saver, scheduler, history);
    }

    ListProfilesUseCase profiles() {
        return profiles;
    }

    StartSessionUseCase sessions() {
        return sessions;
    }

    StateActor state() {
        return state;
    }

    /**
     * 앱을 끝낼 때 한 번 부른다(ADR-0011 · 0012): 상태 실행기를 닫고 남은 명령을 처리한 뒤 → 미저장 이력을 바로 저장(최대 2초) → 저장소를
     * 닫는다(잠금을 푼다). 저장하지 못했으면 저장소를 닫지 않는다. 이 뒤의 명령은 실패한 응답을 받는다.
     */
    void shutdown() {
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
                    case Integration.Unavailable(var reason) ->
                        LOG.log(System.Logger.Level.WARNING, "셸 통합 없음: {0}", reason);
                }
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "셸 통합 설치 실패", e);
            }
        });
    }
}
