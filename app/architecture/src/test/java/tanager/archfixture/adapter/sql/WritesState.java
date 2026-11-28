package tanager.archfixture.adapter.sql;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 허용 픽스처: 도구함이 아닌 어댑터(저장소)는 앱 자신의 파일을 쓴다. */
public final class WritesState {
    void save(Path state) throws IOException {
        Files.writeString(state, "{}");
    }
}
