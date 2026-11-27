package tanager.application.service;

import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import tanager.application.port.out.Cancellable;
import tanager.application.port.out.HistoryStoreError;
import tanager.application.port.out.Scheduler;
import tanager.domain.shared.Result;

/**
 * 값 하나를 <b>첫 미저장 변경 후 1초</b>에 저장하는 일정 조율(throttle, ADR-0013 · FR-RES-01). 변경이 계속 와도 저장이 밀리지 않는다.
 * 저장은 I/O 실행기에서 한 번에 하나만 돌고 그 시점의 최신 값을 쓴다. 저장 중에 기한이 오면 끝나자마자 다음 저장을 한다. 파일에 있다고 아는
 * 값과 같으면 쓰지 않는다. 실패하면 알리고(같은 오류가 이어지면 한 번만) 다음 변경 때 다시 시도한다.
 *
 * <p>상태는 모두 {@link #monitor} 로 지킨다. 변경 알림(actor 스레드) · 예약(스케줄러 스레드) · 저장(I/O 스레드) · {@link #flush}(종료
 * 스레드)가 함께 만진다. 블로킹 I/O(저장)는 monitor 밖에서 한다. 출력 포트의 일정을 조율하는 가변 상태라 규칙 8 의 예외다(ADR-0013).
 *
 * @param <T> 저장하는 값(불변, {@code equals} 로 같음을 판정)
 */
final class ThrottledSaver<T> {
    /** 첫 미저장 변경에서 저장까지. */
    static final Duration DELAY = Duration.ofSeconds(1);

    private static final System.Logger LOG = System.getLogger(ThrottledSaver.class.getName());

    private final String what;
    private final Scheduler scheduler;
    private final Executor io;
    private final Supplier<T> latest;
    private final Function<T, Result<T, HistoryStoreError>> store;
    private final BooleanSupplier writable;
    private final Consumer<HistoryStoreError> announce;
    private final Object monitor = new Object();

    // 아래는 monitor 로 지킨다
    private Optional<Cancellable> timer = Optional.empty();
    private boolean saving;
    // 저장 중에 예약이 돌았다 — 저장이 끝나면 곧바로 다음 저장을 한다(1초 마감)
    private boolean dueWhileSaving;
    private boolean dirty;
    // 파일에 있다고 아는 값(마지막으로 저장했거나 불러온 것). 같으면 다시 쓰지 않는다
    private Optional<T> persisted = Optional.empty();
    // 지금 쓰고 있는 값. 쓰는 동안의 변경은 이것과 비교한다 — 끝나면 파일이 이 값이 된다
    private Optional<T> writing = Optional.empty();
    private Optional<HistoryStoreError> lastFailure = Optional.empty();

    /**
     * @param what 로그에 쓸 이름(예: "이력")
     * @param latest 저장할 최신 값. monitor 안에서 부르므로 막히지 않는 읽기여야 한다
     * @param store 블로킹 저장. 성공하면 쓴 값을 돌려준다
     * @param writable 지금 쓸 수 있는지. 아니면 저장하지 않고 미저장 표시를 남긴다
     * @param announce 저장 실패 알림(직전과 같은 오류는 다시 알리지 않는다). monitor 밖에서 부른다
     */
    ThrottledSaver(
            String what,
            Scheduler scheduler,
            Executor io,
            Supplier<T> latest,
            Function<T, Result<T, HistoryStoreError>> store,
            BooleanSupplier writable,
            Consumer<HistoryStoreError> announce) {
        this.what = Objects.requireNonNull(what, "what");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.io = Objects.requireNonNull(io, "io");
        this.latest = Objects.requireNonNull(latest, "latest");
        this.store = Objects.requireNonNull(store, "store");
        this.writable = Objects.requireNonNull(writable, "writable");
        this.announce = Objects.requireNonNull(announce, "announce");
    }

    /** 파일에 {@code value} 가 있다고 안다(불러온 값). 이와 같은 변경은 쓰지 않는다. */
    void known(T value) {
        Objects.requireNonNull(value, "value");
        synchronized (monitor) {
            persisted = Optional.of(value);
        }
    }

    /** 값이 {@code value} 로 바뀌었다. 파일에 있다고 아는 값(쓰는 중이면 쓰는 값)과 다르면 예약이 없을 때만 1초 뒤로 예약한다. */
    void changed(T value) {
        Objects.requireNonNull(value, "value");
        synchronized (monitor) {
            // 쓰는 중이면 끝난 뒤의 파일과 비교한다. 쓰는 동안 원래 값으로 되돌린 변경을 놓치지 않는다
            if (writing.or(() -> persisted).equals(Optional.of(value))) {
                return;
            }
            dirty = true;
            if (timer.isEmpty()) {
                timer = Optional.of(scheduler.schedule(DELAY, this::due));
            }
        }
    }

    /**
     * 예약을 거두고 미저장 변경을 부른 스레드에서 바로 저장한다(종료 때, ADR-0012). 진행 중인 저장은 {@code timeout} 까지 기다린다. 쓸 수
     * 있는 상태가 아니면 저장하지 않는다.
     *
     * @return 저장할 것이 없었거나 저장했으면 {@code true}. 쓸 수 없는 상태여서 저장하지 않았을 때도 {@code true}. 진행 중 저장이 제때
     *     끝나지 않았거나 저장이 실패했으면 {@code false}
     */
    boolean flush(Duration timeout) {
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
        if (!dirty || !writable.getAsBoolean()) {
            return false;
        }
        saving = true;
        dirty = false;
        return true;
    }

    // monitor 밖(블로킹 I/O). startSave 가 true 를 준 뒤에만 부른다
    private boolean saveLatest() {
        T value;
        boolean unchanged;
        // monitor 안에서 읽는다: 이 뒤의 변경은 changed 가 writing 과 비교하고, 이 앞의 변경은 value 에 들어 있다
        synchronized (monitor) {
            value = latest.get();
            unchanged = persisted.equals(Optional.of(value));
            if (!unchanged) {
                writing = Optional.of(value);
            }
        }
        Result<T, HistoryStoreError> result = unchanged ? Result.ok(value) : save(value);
        return finish(value, result);
    }

    private Result<T, HistoryStoreError> save(T value) {
        try {
            return store.apply(value);
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, what + " 저장소가 예외를 던졌다", e);
            return Result.err(new HistoryStoreError.Io(e.toString()));
        }
    }

    private boolean finish(T value, Result<T, HistoryStoreError> result) {
        Optional<HistoryStoreError> notice = Optional.empty();
        boolean again;
        boolean saved = result.error().isEmpty();
        synchronized (monitor) {
            saving = false;
            writing = Optional.empty();
            if (saved) {
                persisted = Optional.of(value);
                lastFailure = Optional.empty();
            } else {
                HistoryStoreError error = result.error().orElseThrow();
                dirty = true;
                if (!lastFailure.equals(Optional.of(error))) {
                    notice = Optional.of(error);
                }
                lastFailure = Optional.of(error);
            }
            again = dueWhileSaving && startSave();
            dueWhileSaving = false;
            monitor.notifyAll();
        }
        notice.ifPresent(error -> {
            LOG.log(Level.WARNING, "{0} 저장 실패: {1}", what, error.describe());
            announce.accept(error);
        });
        if (again) {
            io.execute(this::saveLatest);
        }
        return saved;
    }
}
