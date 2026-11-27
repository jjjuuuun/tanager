package tanager.domain.history;

/** 별칭으로 쓸 수 없는 이유. 화면 문장은 UI 가 언어에 맞춰 만든다(#137, ADR-0017). */
public enum AliasProblem {
    /** 비었다(앞뒤 공백을 벗긴 뒤). */
    BLANK,
    /** {@link Alias#MAX_CODE_POINTS} 코드 포인트를 넘는다. */
    TOO_LONG,
    /** 제어 · 서식(방향 제어, 폭 없는 문자) · 줄 구분 문자가 들어 있다. */
    INVISIBLE_CHARACTER,
    /** 공백류(NBSP 포함)만으로 이루어져 보이는 글자가 없다. */
    NO_VISIBLE_CHARACTER;

    /** 개발자용 설명(예외 · 로그). */
    String describe() {
        return switch (this) {
            case BLANK -> "별칭은 비어 있거나 앞뒤에 공백이 있으면 안 된다";
            case TOO_LONG -> "별칭은 " + Alias.MAX_CODE_POINTS + " 글자를 넘을 수 없다";
            case INVISIBLE_CHARACTER -> "별칭에 제어 · 서식 문자가 들어 있다";
            case NO_VISIBLE_CHARACTER -> "별칭은 보이는 글자가 있어야 한다";
        };
    }
}
