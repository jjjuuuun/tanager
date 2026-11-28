package tanager.domain.history;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * AI 도구의 이전 대화 하나(FR-TK-09). 트리 투영의 입력이라 {@link SessionLeaf} 와 같은 자리(history)에 둔다. {@code title} 은 첫 사용자
 * 메시지의 앞부분을 {@link #summarize} 로 줄인 것이고, 없으면 empty. 대화 내용은 개인 정보라(NFR-06) 메모리에만 둔다 — 저장 · 로그하지 않는다.
 * 불변식: title 이 있으면 이미 요약 규칙대로다({@code summarize(title) == title}).
 */
public record PastConversation(
        VisitSource source, ConversationId id, WorkspacePath cwd, Optional<String> title, Instant lastActive) {

    /** 요약의 최대 길이(코드 포인트). */
    public static final int TITLE_MAX_CODE_POINTS = 80;

    public PastConversation {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(cwd, "cwd");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(lastActive, "lastActive");
        if (title.isPresent() && !summarize(title.get()).equals(title)) {
            throw new IllegalArgumentException("대화 제목이 요약 규칙(공백 접기 · 80 코드 포인트 · 제어 문자 없음)에 맞지 않는다");
        }
    }

    /**
     * 원문을 목록에 그릴 요약으로 줄인다. 공백류(줄바꿈 · 탭 포함)는 하나로 접고, 제어 · 서식(방향 제어, 폭 없는 문자) 문자는 지우고, 앞뒤 공백을
     * 벗겨 {@value #TITLE_MAX_CODE_POINTS} 코드 포인트로 자른다. 보이는 글자가 없으면 empty.
     */
    public static Optional<String> summarize(String raw) {
        Objects.requireNonNull(raw, "raw");
        StringBuilder out = new StringBuilder();
        int count = 0;
        boolean pendingSpace = false;
        for (int i = 0; i < raw.length() && count < TITLE_MAX_CODE_POINTS; ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                pendingSpace = count > 0;
            } else if (!isInvisible(cp)) {
                if (pendingSpace) {
                    out.append(' ');
                    count++;
                    pendingSpace = false;
                    if (count == TITLE_MAX_CODE_POINTS) {
                        break;
                    }
                }
                out.appendCodePoint(cp);
                count++;
            }
        }
        String s = out.toString().strip();
        return s.isEmpty() ? Optional.empty() : Optional.of(s);
    }

    private static boolean isInvisible(int cp) {
        int type = Character.getType(cp);
        return Character.isISOControl(cp)
                || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR;
    }
}
