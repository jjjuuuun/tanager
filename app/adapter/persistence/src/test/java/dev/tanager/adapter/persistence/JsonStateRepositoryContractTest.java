package dev.tanager.adapter.persistence;

import dev.tanager.application.port.out.HistoryRepository;
import dev.tanager.application.port.out.HistoryRepositoryContract;
import dev.tanager.domain.history.HistoryPolicy;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

/** JSON 파일 구현이 저장소 계약을 지키는지 확인한다. */
class JsonStateRepositoryContractTest extends HistoryRepositoryContract {
    @TempDir
    Path dir;

    private JsonStateRepository repository;

    @Override
    protected HistoryRepository repository() {
        if (repository == null) {
            repository = new JsonStateRepository(
                    dir.resolve("state.json"),
                    HistoryPolicy.DEFAULT,
                    InstantSource.fixed(Instant.parse("2026-11-26T01:02:03Z")));
        }
        return repository;
    }

    @AfterEach
    void releaseLock() {
        if (repository != null) {
            repository.close();
        }
    }
}
