package tanager.application.port.out;

import java.util.List;
import tanager.domain.history.HistoryPolicy;
import tanager.domain.history.WorkHistory;
import tanager.domain.shared.Result;

/** Fake 가 계약을 지키는지 확인한다(다른 테스트가 Fake 를 믿어도 되는지). */
class InMemoryHistoryRepositoryContractTest extends HistoryRepositoryContract {
    private final InMemoryHistoryRepository repository = new InMemoryHistoryRepository();

    @Override
    protected HistoryRepository repository() {
        return repository;
    }

    @Override
    protected void arrangeReadOnly() {
        repository.willLoad(Result.ok(
                new LoadedHistory(WorkHistory.empty(HistoryPolicy.DEFAULT), StoreAccess.READ_ONLY, List.of())));
    }

    @Override
    protected void arrangeLoadFailure() {
        repository.willLoad(Result.err(new HistoryStoreError.Io("디스크 없음")));
    }
}
