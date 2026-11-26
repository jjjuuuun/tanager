package dev.tanager.adapter.persistence;

import dev.tanager.application.port.out.ConfigRepository;
import dev.tanager.application.port.out.ConfigRepositoryContract;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;

/** JSON 파일 구현이 저장소 계약을 지키는지 확인한다. */
class JsonConfigRepositoryContractTest extends ConfigRepositoryContract {
    @TempDir
    Path dir;

    @Override
    protected ConfigRepository repository() {
        return new JsonConfigRepository(dir.resolve("config.json"));
    }
}
