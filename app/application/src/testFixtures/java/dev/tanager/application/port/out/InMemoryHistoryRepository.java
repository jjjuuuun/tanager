package dev.tanager.application.port.out;

import dev.tanager.domain.history.HistoryPolicy;
import dev.tanager.domain.history.WorkHistory;
import dev.tanager.domain.shared.Result;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link HistoryRepository} 의 수작업 Fake. 기본은 저장한 이력을 그대로 돌려주는 메모리 저장소이고, 테스트가 {@code load} 결과나
 * {@code save} 실패를 지정할 수 있다. 호출을 기록한다. 스레드 안전.
 */
public final class InMemoryHistoryRepository implements HistoryRepository {
    private WorkHistory stored = WorkHistory.empty(HistoryPolicy.DEFAULT);
    private Optional<Result<LoadedHistory, HistoryStoreError>> loadOverride = Optional.empty();
    private Optional<HistoryStoreError> saveFailure = Optional.empty();
    private final List<WorkHistory> saves = new ArrayList<>();
    private int loadCalls;

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

    @Override
    public synchronized Result<LoadedHistory, HistoryStoreError> load() {
        loadCalls++;
        return loadOverride.orElseGet(() -> Result.ok(new LoadedHistory(stored, StoreAccess.WRITABLE, List.of())));
    }

    @Override
    public synchronized Result<WorkHistory, HistoryStoreError> save(WorkHistory history) {
        saves.add(history);
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
