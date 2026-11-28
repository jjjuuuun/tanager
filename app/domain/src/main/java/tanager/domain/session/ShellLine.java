package tanager.domain.session;

import java.util.Objects;

/**
 * 셸에 입력할 한 줄(예: {@code claude --resume <id>}). Enter 는 넣지 않는다 — 실행은 부르는 쪽이 정한다. 불변식: 1~512 코드 포인트 · 제어
 * 문자(줄바꿈 포함) 없음.
 */
public record ShellLine(String text) {

    /** 한 줄의 최대 길이(코드 포인트). */
    public static final int MAX_CODE_POINTS = 512;

    public ShellLine {
        Objects.requireNonNull(text, "text");
        if (text.isEmpty() || text.codePointCount(0, text.length()) > MAX_CODE_POINTS) {
            throw new IllegalArgumentException("셸 입력 줄은 1~" + MAX_CODE_POINTS + " 코드 포인트여야 한다");
        }
        if (text.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("셸 입력 줄에 제어 문자가 있다");
        }
    }
}
