package tanager.archfixture.adapter.toolkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 위반 픽스처: 도구함 어댑터가 사용자 설정 파일을 쓴다(FR-TK-07). */
public final class WritesSettings {
    void save(Path settings) throws IOException {
        Files.writeString(settings, "{}");
    }
}
