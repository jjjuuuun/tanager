package tanager.adapter.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import tanager.application.port.out.HistoryRepository;
import tanager.application.port.out.HistoryRepositoryContract;
import tanager.domain.history.HistoryPolicy;

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

    // 상위 버전(schemaVersion 3) 파일은 읽기 전용으로 열린다
    @Override
    protected void arrangeReadOnly() throws IOException {
        try (InputStream in = JsonStateRepositoryContractTest.class.getResourceAsStream("/state/v3-newer.json")) {
            Files.write(dir.resolve("state.json"), in.readAllBytes());
        }
    }

    // state.json 자리를 폴더로 막아 읽기를 실패시킨다
    @Override
    protected void arrangeLoadFailure() throws IOException {
        Files.createDirectories(dir.resolve("state.json"));
    }

    @AfterEach
    void releaseLock() {
        if (repository != null) {
            repository.close();
        }
    }
}
