package tanager.domain.toolkit;

/**
 * 항목이 정의된 범위(FR-TK-03). 선언 순서 = 화면 순서(MANAGED 가 맨 위)이며 우선순위가 아니다 — 우선순위는 공급자가 {@link
 * ScopePrecedence} 로 정한다.
 */
public enum ToolkitScope {
    MANAGED,
    LOCAL,
    PROJECT,
    PARENT,
    USER
}
