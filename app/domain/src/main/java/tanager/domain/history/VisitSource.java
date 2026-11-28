package tanager.domain.history;

import java.util.Objects;

/**
 * 이력 항목 · 대화의 출처(셸, Claude Code …). 불변식: {@code [a-z0-9][a-z0-9-]{0,31}}. 모르는 값(다음 버전이 쓴 값)도 형식이 맞으면 그대로
 * 담는다.
 */
public record VisitSource(String id) {

    /** 셸이 알린 cwd(OSC 7 · OSC 9;9). */
    public static final VisitSource SHELL = new VisitSource("shell");

    /** Claude Code 의 대화 기록(FR-TK-09). */
    public static final VisitSource CLAUDE_CODE = new VisitSource("claude-code");

    private static final int MAX_LENGTH = 32;

    public VisitSource {
        Objects.requireNonNull(id, "id");
        if (!isValid(id)) {
            throw new IllegalArgumentException("출처 형식이 틀렸다([a-z0-9][a-z0-9-]{0,31}): " + id);
        }
    }

    private static boolean isValid(String s) {
        if (s.isEmpty() || s.length() > MAX_LENGTH || s.charAt(0) == '-') {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-')) {
                return false;
            }
        }
        return true;
    }
}
