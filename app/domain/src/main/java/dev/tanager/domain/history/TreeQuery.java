package dev.tanager.domain.history;

import java.util.List;
import java.util.Objects;

/**
 * 트리 투영의 조건. {@code text} 는 검색어(앞뒤 공백뿐이면 거르지 않음), {@code showHidden} 은 숨긴 항목을 보일지,
 * {@code environmentOrder} 는 환경 노드 순서(셸 탐지 순서)다.
 */
public record TreeQuery(String text, boolean showHidden, List<ShellEnvironment> environmentOrder) {
    public TreeQuery {
        Objects.requireNonNull(text, "text");
        environmentOrder = List.copyOf(environmentOrder);
    }
}
