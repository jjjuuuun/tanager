package dev.tanager.application.port.out;

/** Fake 가 계약을 지키는지 확인한다(다른 테스트가 Fake 를 믿어도 되는지). */
class InMemoryHistoryRepositoryContractTest extends HistoryRepositoryContract {
    private final InMemoryHistoryRepository repository = new InMemoryHistoryRepository();

    @Override
    protected HistoryRepository repository() {
        return repository;
    }
}
