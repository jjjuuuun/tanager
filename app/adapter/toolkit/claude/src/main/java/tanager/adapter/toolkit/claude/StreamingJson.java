package tanager.adapter.toolkit.claude;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import tanager.domain.shared.Result;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 구성 JSON 을 스트리밍으로 읽는 공통 도우미({@link McpJson} · {@link SettingsJson}). 필요 없는 값은 {@link JsonParser#skipChildren}
 * 으로 건너뛰어 문자열로 만들지 않는다 — 비밀값이 메모리 · 예외에 남지 않게.
 *
 * <p>형식 오류는 줄 번호만 돌려준다. Jackson 예외 메시지에는 원문 조각(비밀값일 수 있다)이 들어 있어 어디에도 쓰지 않는다.
 */
final class StreamingJson {

    /** 맨 위 객체의 몸통 읽기. 파서는 {@code START_OBJECT} 에 있다. */
    @FunctionalInterface
    interface Body<T> {
        T read(JsonParser p);
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private StreamingJson() {}

    /**
     * 맨 위가 객체인 JSON 을 읽는다(스트림을 닫는 것은 부르는 쪽). 객체가 아니거나 형식이 틀리면 줄 번호만 든 {@link FormatError}.
     *
     * @throws IOException 읽기 실패(형식 오류가 아니다)
     */
    static <T> Result<T, FormatError> object(InputStream in, Body<T> body) throws IOException {
        try (JsonParser p = JSON.createParser(in)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                return Result.err(new FormatError(lineOf(p.currentTokenLocation())));
            }
            return Result.ok(body.read(p));
        } catch (JacksonIOException e) {
            throw e.getCause();
        } catch (JacksonException e) {
            return Result.err(new FormatError(lineOf(e.getLocation())));
        }
    }

    /** 지금 값이 객체가 아니면 건너뛰고 false. */
    static boolean enterObject(JsonParser p) {
        if (p.currentToken() == JsonToken.START_OBJECT) {
            return true;
        }
        p.skipChildren();
        return false;
    }

    /** 지금 값이 배열이 아니면 건너뛰고 false. */
    static boolean enterArray(JsonParser p) {
        if (p.currentToken() == JsonToken.START_ARRAY) {
            return true;
        }
        p.skipChildren();
        return false;
    }

    /** 배열 안의 다음 원소 토큰. 배열이 끝나면 empty. */
    static Optional<JsonToken> nextElement(JsonParser p) {
        JsonToken token = p.nextToken();
        return token == null || token == JsonToken.END_ARRAY ? Optional.empty() : Optional.of(token);
    }

    /** null 이 아닌 스칼라 값(숫자 · 불도 글자로). 다른 값은 건너뛴다. */
    static Optional<String> scalar(JsonParser p) {
        JsonToken token = p.currentToken();
        if (token.isScalarValue() && token != JsonToken.VALUE_NULL) {
            return Optional.of(p.getValueAsString());
        }
        p.skipChildren();
        return Optional.empty();
    }

    /** 문자열 값만. 다른 값은 건너뛴다. */
    static Optional<String> string(JsonParser p) {
        if (p.currentToken() == JsonToken.VALUE_STRING) {
            return Optional.of(p.getValueAsString());
        }
        p.skipChildren();
        return Optional.empty();
    }

    /** 숫자 값만(글자로). 다른 값은 건너뛴다. */
    static Optional<String> number(JsonParser p) {
        if (p.currentToken().isNumeric()) {
            return Optional.of(p.getValueAsString());
        }
        p.skipChildren();
        return Optional.empty();
    }

    /** 배열의 스칼라 값. 배열이 아니면 빈 목록. */
    static List<String> scalars(JsonParser p) {
        List<String> values = new ArrayList<>();
        if (enterArray(p)) {
            while (nextElement(p).isPresent()) {
                scalar(p).ifPresent(values::add);
            }
        }
        return values;
    }

    /** 배열의 문자열 값만. 배열이 아니면 빈 목록. */
    static List<String> strings(JsonParser p) {
        List<String> values = new ArrayList<>();
        if (enterArray(p)) {
            while (nextElement(p).isPresent()) {
                string(p).ifPresent(values::add);
            }
        }
        return values;
    }

    /** 객체의 키만. 값은 건너뛴다 — 파서가 값의 글자를 문자열로 만들지 않는다. */
    static List<String> keys(JsonParser p) {
        List<String> keys = new ArrayList<>();
        if (enterObject(p)) {
            for (String key = p.nextName(); key != null; key = p.nextName()) {
                p.nextToken();
                p.skipChildren();
                keys.add(key);
            }
        }
        return keys;
    }

    private static OptionalInt lineOf(TokenStreamLocation location) {
        if (location == null || location.getLineNr() < 1) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(location.getLineNr());
    }
}
