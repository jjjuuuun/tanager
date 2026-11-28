package tanager.adapter.persistence;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/** state.json 의 값 읽기 도우미. 키가 없으면({@code null}) 틀린 값과 같이 비어 있다. */
final class JsonValues {
    private JsonValues() {}

    /** 문자열 값. */
    static Optional<String> string(JsonNode node) {
        return node != null && node.isString() ? Optional.of(node.stringValue()) : Optional.empty();
    }

    /** 비어 있지 않은 문자열 값(ID · 프로파일). */
    static Optional<String> nonBlank(JsonNode node) {
        return string(node).filter(s -> !s.isBlank());
    }

    /** ISO-8601 시각 문자열({@code Instant.toString} 모양). */
    static Optional<Instant> instant(JsonNode node) {
        try {
            return string(node).map(Instant::parse);
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
