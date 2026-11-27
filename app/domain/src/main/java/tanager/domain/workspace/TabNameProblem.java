package tanager.domain.workspace;

/** 탭 이름으로 쓸 수 없는 이유(#176, FR-LAY-07). */
public sealed interface TabNameProblem {

    /** 비었거나 공백뿐이다. */
    record Blank() implements TabNameProblem {}

    /** 앞뒤 공백을 뗀 길이(코드 포인트)가 {@link TabName#MAX_LENGTH} 를 넘는다. */
    record TooLong(int codePoints) implements TabNameProblem {}

    /** 제어 문자가 들어 있다(줄바꿈 · 탭 · 벨 등). */
    record ControlCharacter(int codePoint) implements TabNameProblem {}
}
