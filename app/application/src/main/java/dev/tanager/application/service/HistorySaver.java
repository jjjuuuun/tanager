package dev.tanager.application.service;

import dev.tanager.application.port.in.AppEvent;
import dev.tanager.application.port.out.Cancellable;
import dev.tanager.application.port.out.HistoryRepository;
import dev.tanager.application.port.out.HistoryStoreError;
import dev.tanager.application.port.out.Scheduler;
import dev.tanager.application.state.HistoryStore;
import dev.tanager.application.state.StateActor;
import dev.tanager.application.state.Transition;
import dev.tanager.domain.history.WorkHistory;
import dev.tanager.domain.shared.Result;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * 이력이 바뀌면 <b>첫 미저장 변경 후 1초</b>에 저장한다(throttle, ADR-0013 · FR-RES-01). 변경이 계속 와도 저장이 밀리지 않는다. 저장은
 * I/O 실행기에서 한 번에 하나만 돌고, 그 시점의 최신 이력을 쓴다. 저장소가 쓸 수 있는 상태일 때만 저장한다 — 불러오는 중에 빈 이력으로
 * 파일을 덮지 않는다. 실패하면 알리고(같은 오류가 이어지면 한 번만) 다음 변경 때 다시 시도한다.
 *
 * <p>상태는 모두 {@link #monitor} 로 지킨다. 이벤트 콜백(actor 스레드) · 예약(스케줄러 스레드) · 저장(I/O 스레드) · {@link #flush}
 * (종료 스레드)가 함께 만진다. 출력 포트의 일정을 조율하는 가변 상태라 규칙 8 의 예외로 application 에 둔다(ADR-0013).
 */
public final class HistorySaver {
    /** 첫 미저장 변경에서 저장까지. */
    static final Duration DELAY = Duration.ofSeconds(1);

    private static final System.Logger LOG = System.getLogger(HistorySaver.class.getName());

    private final StateActor state;
    private final HistoryRepository repository;
    private final Scheduler scheduler;
    private final Executor io;
    private final Object monitor = new Object();

    // 아래는 monitor 로 지킨다
    private Optional<Cancellable> timer = Optional.empty();
    private boolean saving;
    // 저장 중에 예약이 돌았다 — 저장이 끝나면 곧바로 다음 저장을 한다(1초 마감)
    private boolean dueWhileSaving;
    private boolean dirty;
    // 파일에 있다고 아는 이력(마지막으로 저장했거나 불러온 것). 같으면 다시 쓰지 않는다
    private Optional<WorkHistory> persisted = Optional.empty();
    // 지금 쓰고 있는 이력. 쓰는 동안의 변경은 이것과 비교한다 — 끝나면 파일이 이 값이 된다
    private Optional<WorkHistory> writing = Optional.empty();
    private Optional<HistoryStoreError> lastFailure = Optional.empty();
    private boolean loading;
    private boolean visitedWhileLoading;

    /**
     * 만들면서 {@code state} 의 이벤트를 구독한다. 다른 스레드가 {@code state} 에 명령(방문 · 불러오기)을 내기 전에 만든다 — 스냅샷을 읽은 뒤 구독하기 전에 확정된 전이는 보지 못한다. 만들 때 이미 쌓여 있던 방문(불러오는 중의 방문)도 불러온 뒤
     * 저장할 것으로 센다.
     */
    public HistorySaver(StateActor state, HistoryRepository repository, Scheduler scheduler, Executor io) {
        this.state = Objects.requireNonNull(state, "state");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.io = Objects.requireNonNull(io, "io");
        HistoryStore store = state.snapshot().store();
        synchronized (monitor) {
            this.loading = store instanceof HistoryStore.Loading;
            this.visitedWhileLoading =
                    store instanceof HistoryStore.Loading l && !l.pending().isEmpty();
        }
        state.subscribe(this::onEvent);
    }

    /**
     * 예약을 거두고 미저장 변경을 부른 스레드에서 바로 저장한다(종료 때, ADR-0012). 진행 중인 저장은 {@code timeout} 까지 기다린다. 쓸 수
     * 있는 상태가 아니면 저장하지 않는다.
     *
     * @return 저장할 것이 없었거나 저장했으면 {@code true}. 쓸 수 없는 상태(ReadOnly · Loading)여서 저장하지 않았을 때도 {@code
     *     true}. 진행 중 저장이 제때 끝나지 않았거나 저장이 실패했으면 {@code false}
     */
    public boolean flush(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (monitor) {
            timer.ifPresent(Cancellable::cancel);
            timer = Optional.empty();
            // 진행 중 저장이 끝나도 다음 저장을 I/O 로 넘기지 않고 여기서 한다
            dueWhileSaving = false;
            while (saving) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(monitor, left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            if (!startSave()) {
                return true;
            }
        }
        return saveLatest();
    }

    // actor 스레드. 콜백 시점의 snapshot 이 이 이벤트를 낸 전이의 결과다
    private void onEvent(AppEvent event) {
        if (!(event instanceof AppEvent.HistoryChanged(WorkHistory history))) {
            return;
        }
        HistoryStore store = state.snapshot().store();
        synchronized (monitor) {
            switch (store) {
                case HistoryStore.Loading(var pending) -> {
                    loading = true;
                    visitedWhileLoading = !pending.isEmpty();
                }
                case HistoryStore.ReadOnly(var _) -> loading = false;
                case HistoryStore.Writable() -> changed(history);
            }
        }
    }

    // monitor 안. 불러오기만 한 이력은 파일과 같다 — 불러오는 동안 방문이 있었을 때만 저장할 것이 있다
    private void changed(WorkHistory history) {
        if (loading) {
            loading = false;
            if (!visitedWhileLoading) {
                persisted = Optional.of(history);
            }
        }
        // 쓰는 중이면 끝난 뒤의 파일과 비교한다. 쓰는 동안 원래 값으로 되돌린 변경을 놓치지 않는다
        if (writing.or(() -> persisted).equals(Optional.of(history))) {
            return;
        }
        dirty = true;
        if (timer.isEmpty()) {
            timer = Optional.of(scheduler.schedule(DELAY, this::due));
        }
    }

    // 스케줄러 스레드
    private void due() {
        synchronized (monitor) {
            timer = Optional.empty();
            if (saving) {
                dueWhileSaving = true;
                return;
            }
            if (!startSave()) {
                return;
            }
        }
        io.execute(this::saveLatest);
    }

    // monitor 안. true 면 부른 쪽이 saveLatest 를 한 번 돌려야 한다
    private boolean startSave() {
        if (!dirty || !(state.snapshot().store() instanceof HistoryStore.Writable)) {
            return false;
        }
        saving = true;
        dirty = false;
        return true;
    }

    // monitor 밖(블로킹 I/O). startSave 가 true 를 준 뒤에만 부른다
    private boolean saveLatest() {
        WorkHistory latest;
        boolean unchanged;
        // monitor 안에서 읽는다: 이 뒤의 변경은 콜백이 writing 과 비교하고, 이 앞의 변경은 latest 에 들어 있다
        synchronized (monitor) {
            latest = state.snapshot().history();
            unchanged = persisted.equals(Optional.of(latest));
            if (!unchanged) {
                writing = Optional.of(latest);
            }
        }
        Result<WorkHistory, HistoryStoreError> result = unchanged ? Result.ok(latest) : save(latest);
        return finish(latest, result);
    }

    private Result<WorkHistory, HistoryStoreError> save(WorkHistory latest) {
        try {
            return repository.save(latest);
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "이력 저장소가 예외를 던졌다", e);
            return Result.err(new HistoryStoreError.Io(e.toString()));
        }
    }

    private boolean finish(WorkHistory latest, Result<WorkHistory, HistoryStoreError> result) {
        Optional<String> notice = Optional.empty();
        boolean again;
        boolean saved = result.error().isEmpty();
        synchronized (monitor) {
            saving = false;
            writing = Optional.empty();
            if (saved) {
                persisted = Optional.of(latest);
                lastFailure = Optional.empty();
            } else {
                HistoryStoreError error = result.error().orElseThrow();
                dirty = true;
                if (!lastFailure.equals(Optional.of(error))) {
                    notice = Optional.of(error.describe());
                }
                lastFailure = Optional.of(error);
            }
            again = dueWhileSaving && startSave();
            dueWhileSaving = false;
            monitor.notifyAll();
        }
        notice.ifPresent(this::announce);
        if (again) {
            io.execute(this::saveLatest);
        }
        return saved;
    }

    private void announce(String message) {
        LOG.log(Level.WARNING, "이력 저장 실패: {0}", message);
        state.submit(current -> new Transition<>(current, List.of(new AppEvent.StoreNotice(message)), true));
    }
}
