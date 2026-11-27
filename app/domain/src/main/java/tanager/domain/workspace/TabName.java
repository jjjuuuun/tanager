package tanager.domain.workspace;

import java.util.Objects;
import java.util.Optional;
import tanager.domain.shared.Result;

/**
 * 사용자가 붙인 탭 이름(#176, FR-LAY-07). 불변식: 앞뒤 공백이 없고 1~{@value #MAX_LENGTH} 코드 포인트이며 제어 문자가 없다. 길이를 코드
 * 포인트로 세는 것은 이모지처럼 char 두 개인 글자도 한 글자로 보이기 때문이다.
 */
public record TabName(String value) {
    /** 이름의 최대 길이(코드 포인트). */
    public static final int MAX_LENGTH = 40;

    public TabName {
        Objects.requireNonNull(value, "value");
        if (!value.equals(value.strip())) {
            throw new IllegalArgumentException("탭 이름 앞뒤에 공백이 있다");
        }
        Optional<TabNameProblem> problem = problem(value);
        if (problem.isPresent()) {
            throw new IllegalArgumentException("탭 이름으로 쓸 수 없다: " + problem.get());
        }
    }

    /** 사용자가 친 글자에서 앞뒤 공백을 떼어 만든다. */
    public static Result<TabName, TabNameProblem> of(String raw) {
        Objects.requireNonNull(raw, "raw");
        String stripped = raw.strip();
        return problem(stripped)
                .<Result<TabName, TabNameProblem>>map(Result::err)
                .orElseGet(() -> Result.ok(new TabName(stripped)));
    }

    private static Optional<TabNameProblem> problem(String stripped) {
        if (stripped.isEmpty()) {
            return Optional.of(new TabNameProblem.Blank());
        }
        int length = stripped.codePointCount(0, stripped.length());
        if (length > MAX_LENGTH) {
            return Optional.of(new TabNameProblem.TooLong(length));
        }
        return stripped.codePoints()
                .filter(Character::isISOControl)
                .<TabNameProblem>mapToObj(TabNameProblem.ControlCharacter::new)
                .findFirst();
    }
}
