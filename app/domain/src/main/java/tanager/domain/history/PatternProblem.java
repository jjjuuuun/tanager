package tanager.domain.history;

import java.util.Objects;

/** 이력 제외 규칙({@link PathPattern})으로 쓸 수 없는 이유. */
public sealed interface PatternProblem {

    /** 비어 있거나 공백뿐이다. */
    record Blank() implements PatternProblem {}

    /** 절대 경로 glob 이 아니다 — {@code /} · 드라이브({@code C:\}) · UNC({@code \\}) · {@code **} 로 시작하지 않는다. */
    record NotAbsolute(String raw) implements PatternProblem {
        public NotAbsolute {
            Objects.requireNonNull(raw, "raw");
        }
    }
}
