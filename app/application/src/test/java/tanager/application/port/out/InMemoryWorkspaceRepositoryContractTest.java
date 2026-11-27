package tanager.application.port.out;

import java.util.List;
import java.util.Optional;
import tanager.domain.shared.Result;

/** Fake 가 계약을 지키는지 확인한다(다른 테스트가 Fake 를 믿어도 되는지). */
class InMemoryWorkspaceRepositoryContractTest extends WorkspaceRepositoryContract {
    private final InMemoryWorkspaceRepository repository = new InMemoryWorkspaceRepository();

    @Override
    protected WorkspaceRepository repository() {
        return repository;
    }

    @Override
    protected void arrangeReadOnly() {
        repository.willLoad(Result.ok(new LoadedWorkspace(Optional.empty(), StoreAccess.READ_ONLY, List.of())));
    }

    @Override
    protected void arrangeLoadFailure() {
        repository.willLoad(Result.err(new HistoryStoreError.Io("디스크 없음")));
    }
}
