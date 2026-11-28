package tanager.domain.toolkit;

import java.util.Objects;
import tanager.domain.history.WorkspacePath;

/** 항목의 원본 파일. 그 환경의 경로다(경로 복사 · 파일 열기용, FR-TK-06). */
public record SourceRef(WorkspacePath file) {
    public SourceRef {
        Objects.requireNonNull(file, "file");
    }
}
