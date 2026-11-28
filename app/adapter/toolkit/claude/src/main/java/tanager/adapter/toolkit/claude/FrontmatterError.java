package tanager.adapter.toolkit.claude;

import java.util.Objects;
import java.util.OptionalInt;

/** 프런트매터 형식 오류. 파일 안의 줄 번호(1부터, 여는 {@code ---} 가 1번 줄)만 담는다 — 파서 메시지에는 원문 조각이 섞인다. */
record FrontmatterError(OptionalInt line) {
    FrontmatterError {
        Objects.requireNonNull(line, "line");
    }
}
