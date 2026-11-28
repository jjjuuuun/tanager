package tanager.domain.history;

import java.util.Objects;
import java.util.Optional;

/**
 * AI 도구의 대화 하나를 가리키는 ID. 명령줄({@code claude --resume <id>})에 그대로 들어가므로 엄격하다 — 따옴표 · 공백 · 경로 구분자가 끼면
 * 셸 명령으로 주입된다. 불변식: {@code [A-Za-z0-9_-]{1,128}}.
 */
public record ConversationId(String value) {

    /** ID 의 최대 길이. */
    public static final int MAX_LENGTH = 128;

    public ConversationId {
        Objects.requireNonNull(value, "value");
        if (!isValid(value)) {
            throw new IllegalArgumentException("대화 ID 형식이 틀렸다([A-Za-z0-9_-]{1,128})");
        }
    }

    /** 형식에 맞으면 그 ID, 아니면 empty. 파일 이름처럼 밖에서 온 값을 받을 때 쓴다. */
    public static Optional<ConversationId> parse(String raw) {
        Objects.requireNonNull(raw, "raw");
        return isValid(raw) ? Optional.of(new ConversationId(raw)) : Optional.empty();
    }

    private static boolean isValid(String s) {
        if (s.isEmpty() || s.length() > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok =
                    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
