package tanager.application.port.out;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * 공급자가 본 파일 · 디렉터리(앱이 읽은 경로)와 그 수정 시각(없으면 empty)(ADR-0019). 같은 값이면 지난 scan 을 다시 쓴다. 내용은 담지 않는다.
 */
public record ScanStamp(Map<Path, Optional<Instant>> inputs) {
    public ScanStamp {
        inputs = Map.copyOf(inputs);
    }
}
