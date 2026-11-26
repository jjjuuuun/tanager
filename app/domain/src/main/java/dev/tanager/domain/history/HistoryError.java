package dev.tanager.domain.history;

import java.util.Objects;

/** 작업 경로 이력을 바꿀 수 없는 이유. */
public sealed interface HistoryError {

    /** 이력에 그 경로가 없다. */
    record NotFound(WorkspacePath path) implements HistoryError {
        public NotFound {
            Objects.requireNonNull(path, "path");
        }
    }

    /** 별칭으로 쓸 수 없는 값이다(빈 값 · 너무 김 · 제어 문자). */
    record InvalidAlias(String raw, String reason) implements HistoryError {
        public InvalidAlias {
            Objects.requireNonNull(raw, "raw");
            Objects.requireNonNull(reason, "reason");
        }
    }
}
