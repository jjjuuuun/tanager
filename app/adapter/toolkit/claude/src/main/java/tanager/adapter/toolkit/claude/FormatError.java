package tanager.adapter.toolkit.claude;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * 구성 파일의 형식 오류(프런트매터 · MCP JSON). 파일 안의 줄 번호(1부터)만 담는다 — 파서 메시지에는 원문 조각(비밀값일 수 있다)이 섞인다.
 * 프런트매터는 여는 {@code ---} 가 1번 줄이다.
 */
record FormatError(OptionalInt line) {
    FormatError {
        Objects.requireNonNull(line, "line");
    }
}
