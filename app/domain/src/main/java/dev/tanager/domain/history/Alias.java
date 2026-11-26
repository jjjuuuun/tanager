package dev.tanager.domain.history;

import dev.tanager.domain.shared.Result;
import java.util.Objects;
import java.util.Optional;

/**
 * 사용자가 작업 경로에 붙인 이름. 불변식: 앞뒤 공백 없음 · 1~64 코드 포인트 · 제어 · 서식(방향 제어, 폭 없는 문자) · 줄 구분 문자 없음 ·
 * 공백류(NBSP 포함)만으로 이루어지지 않음 — 트리 표시가 비거나 뒤집히지 않게.
 */
public record Alias(String value) {

    /** 별칭의 최대 길이(코드 포인트). */
    public static final int MAX_CODE_POINTS = 64;

    public Alias {
        Objects.requireNonNull(value, "value");
        Optional<String> problem = problem(value);
        if (problem.isPresent()) {
            throw new IllegalArgumentException(problem.get());
        }
    }

    /** 앞뒤 공백을 벗겨 별칭을 만든다. 별칭을 지우는 일은 호출 쪽 몫이라 빈 값도 {@link HistoryError.InvalidAlias} 다. */
    public static Result<Alias, HistoryError.InvalidAlias> of(String raw) {
        Objects.requireNonNull(raw, "raw");
        String stripped = raw.strip();
        return problem(stripped)
                .<Result<Alias, HistoryError.InvalidAlias>>map(
                        reason -> Result.err(new HistoryError.InvalidAlias(raw, reason)))
                .orElseGet(() -> Result.ok(new Alias(stripped)));
    }

    private static Optional<String> problem(String s) {
        if (s.isEmpty() || !s.equals(s.strip())) {
            return Optional.of("별칭은 비어 있거나 앞뒤에 공백이 있으면 안 된다");
        }
        if (s.codePointCount(0, s.length()) > MAX_CODE_POINTS) {
            return Optional.of("별칭은 " + MAX_CODE_POINTS + " 글자를 넘을 수 없다");
        }
        if (s.codePoints().anyMatch(Alias::isInvisibleControl)) {
            return Optional.of("별칭에 제어 · 서식 문자가 들어 있다");
        }
        if (s.codePoints().allMatch(cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp))) {
            return Optional.of("별칭은 보이는 글자가 있어야 한다");
        }
        return Optional.empty();
    }

    private static boolean isInvisibleControl(int cp) {
        int type = Character.getType(cp);
        return Character.isISOControl(cp)
                || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR;
    }
}
