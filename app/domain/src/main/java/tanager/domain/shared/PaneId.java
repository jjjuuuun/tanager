package tanager.domain.shared;

import java.util.Objects;

/** 창(pane) 식별자. 이력 트리 · 레이아웃 · 작업 공간이 같은 창을 가리키는 데 함께 쓴다. */
public record PaneId(String value) {
    public PaneId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("PaneId 가 비어 있다");
        }
    }
}
