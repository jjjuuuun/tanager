package tanager.application.port.out;

/** Fake 가 계약을 지키는지 확인한다(다른 테스트가 Fake 를 믿어도 되는지). */
class InMemoryConfigRepositoryContractTest extends ConfigRepositoryContract {
    private final InMemoryConfigRepository repository = new InMemoryConfigRepository();

    @Override
    protected ConfigRepository repository() {
        return repository;
    }
}
