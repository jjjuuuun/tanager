package tanager.application.port.out;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tanager.domain.shared.Result;
import tanager.domain.workspace.WorkspaceSnapshot;

/**
 * {@link WorkspaceRepository} 의 수작업 Fake. 기본은 저장한 스냅샷을 그대로 돌려주는 메모리 저장소이고, 테스트가 {@code loadWorkspace} 결과나
 * {@code saveWorkspace} 실패를 지정할 수 있다. 실제 구현처럼 마지막 load 가 WRITABLE 로 성공했을 때만 저장한다. 호출을 기록한다. 스레드 안전.
 */
public final class InMemoryWorkspaceRepository implements WorkspaceRepository {
    private Optional<WorkspaceSnapshot> stored = Optional.empty();
    private Optional<Result<LoadedWorkspace, HistoryStoreError>> loadOverride = Optional.empty();
    private Optional<HistoryStoreError> saveFailure = Optional.empty();
    private final List<WorkspaceSnapshot> saves = new ArrayList<>();
    private int loadCalls;
    private Optional<StoreAccess> access = Optional.empty();

    /** 앞으로의 {@code loadWorkspace} 가 이 결과를 돌려준다. */
    public synchronized InMemoryWorkspaceRepository willLoad(Result<LoadedWorkspace, HistoryStoreError> result) {
        this.loadOverride = Optional.of(Objects.requireNonNull(result));
        return this;
    }

    /** 앞으로의 {@code saveWorkspace} 가 이 오류로 실패한다(저장하지 않는다). */
    public synchronized InMemoryWorkspaceRepository willFailSave(HistoryStoreError error) {
        this.saveFailure = Optional.of(Objects.requireNonNull(error));
        return this;
    }

    @Override
    public synchronized Result<LoadedWorkspace, HistoryStoreError> loadWorkspace() {
        loadCalls++;
        Result<LoadedWorkspace, HistoryStoreError> result =
                loadOverride.orElseGet(() -> Result.ok(new LoadedWorkspace(stored, StoreAccess.WRITABLE, List.of())));
        access = result.value().map(LoadedWorkspace::access);
        return result;
    }

    @Override
    public synchronized Result<WorkspaceSnapshot, HistoryStoreError> saveWorkspace(WorkspaceSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        saves.add(snapshot);
        if (access.isEmpty()) {
            return Result.err(new HistoryStoreError.ReadOnly(HistoryStoreError.ReadOnlyReason.NOT_LOADED));
        }
        if (access.get() != StoreAccess.WRITABLE) {
            return Result.err(new HistoryStoreError.ReadOnly(HistoryStoreError.ReadOnlyReason.OTHER_INSTANCE));
        }
        if (saveFailure.isPresent()) {
            return Result.err(saveFailure.get());
        }
        stored = Optional.of(snapshot);
        return Result.ok(snapshot);
    }

    /** {@code saveWorkspace} 에 넘어온 스냅샷들(실패한 호출 포함, 불변 사본). */
    public synchronized List<WorkspaceSnapshot> saves() {
        return List.copyOf(saves);
    }

    public synchronized int loadCalls() {
        return loadCalls;
    }
}
