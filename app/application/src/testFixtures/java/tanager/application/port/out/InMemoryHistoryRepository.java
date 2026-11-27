package tanager.application.port.out;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import tanager.domain.history.HistoryPolicy;
import tanager.domain.history.WorkHistory;
import tanager.domain.shared.Result;

/**
 * {@link HistoryRepository} 의 수작업 Fake. 기본은 저장한 이력을 그대로 돌려주는 메모리 저장소이고, 테스트가 {@code load} 결과나
 * {@code save} 실패를 지정할 수 있다. 실제 구현처럼 마지막 {@code load} 가 WRITABLE 로 성공했을 때만 저장한다. 호출을 기록한다. 스레드 안전.
 */
public final class InMemoryHistoryRepository implements HistoryRepository {
    private WorkHistory stored = WorkHistory.empty(HistoryPolicy.DEFAULT);
    private Optional<Result<LoadedHistory, HistoryStoreError>> loadOverride = Optional.empty();
    private Optional<HistoryStoreError> saveFailure = Optional.empty();
    private final List<WorkHistory> saves = new ArrayList<>();
    private int loadCalls;
    private Optional<StoreAccess> access = Optional.empty();
    private volatile Optional<SaveGate> gate = Optional.empty();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();

    private record SaveGate(CountDownLatch entered, CountDownLatch release) {}

    /** 앞으로의 {@code load} 가 이 결과를 돌려준다. */
    public synchronized InMemoryHistoryRepository willLoad(Result<LoadedHistory, HistoryStoreError> result) {
        this.loadOverride = Optional.of(Objects.requireNonNull(result));
        return this;
    }

    /** 앞으로의 {@code save} 가 이 오류로 실패한다(저장하지 않는다). */
    public synchronized InMemoryHistoryRepository willFailSave(HistoryStoreError error) {
        this.saveFailure = Optional.of(Objects.requireNonNull(error));
        return this;
    }

    /** 앞으로의 {@code save} 가 성공한다(앞서 지정한 실패를 거둔다). */
    public synchronized InMemoryHistoryRepository willSucceedSave() {
        this.saveFailure = Optional.empty();
        return this;
    }

    /**
     * 앞으로의 {@code save} 가 들어오면 {@code entered} 를 내리고 {@code release} 가 열릴 때까지 막는다(느린 디스크 흉내). 막힌 동안에도 다른
     * 메서드는 쓸 수 있다.
     */
    public InMemoryHistoryRepository willBlockSave(CountDownLatch entered, CountDownLatch release) {
        this.gate = Optional.of(new SaveGate(Objects.requireNonNull(entered), Objects.requireNonNull(release)));
        return this;
    }

    /** 지금까지 동시에 진행 중이던 {@code save} 의 최대 수. */
    public int maxConcurrentSaves() {
        return maxInFlight.get();
    }

    @Override
    public synchronized Result<LoadedHistory, HistoryStoreError> load() {
        loadCalls++;
        Result<LoadedHistory, HistoryStoreError> result =
                loadOverride.orElseGet(() -> Result.ok(new LoadedHistory(stored, StoreAccess.WRITABLE, List.of())));
        access = result.value().map(LoadedHistory::access);
        return result;
    }

    @Override
    public Result<WorkHistory, HistoryStoreError> save(WorkHistory history) {
        int now = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(now, Math::max);
        record(history);
        try {
            Optional<SaveGate> blocking = gate;
            if (blocking.isPresent()) {
                blocking.get().entered().countDown();
                try {
                    blocking.get().release().await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return saveNow(history);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    // 막힌 동안에도 넘어온 이력이 saves() 에 보이게 문 앞에서 기록한다
    private synchronized void record(WorkHistory history) {
        saves.add(history);
    }

    private synchronized Result<WorkHistory, HistoryStoreError> saveNow(WorkHistory history) {
        if (!access.equals(Optional.of(StoreAccess.WRITABLE))) {
            return Result.err(
                    new HistoryStoreError.ReadOnly(access.isEmpty() ? "불러오지 않았거나 불러오기가 실패했다" : "읽기 전용으로 불러왔다"));
        }
        if (saveFailure.isPresent()) {
            return Result.err(saveFailure.get());
        }
        stored = history;
        return Result.ok(history);
    }

    /** {@code save} 에 넘어온 이력들(실패한 호출 포함, 불변 사본). */
    public synchronized List<WorkHistory> saves() {
        return List.copyOf(saves);
    }

    public synchronized int loadCalls() {
        return loadCalls;
    }
}
