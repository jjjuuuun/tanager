package tanager.domain.shared;

import java.util.Objects;

/** 탭 식별자. 레이아웃 · 작업 공간 스냅샷이 탭을 가리키는 데 쓴다. */
public record TabId(String value) {
    public TabId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("TabId 가 비어 있다");
        }
    }
}
