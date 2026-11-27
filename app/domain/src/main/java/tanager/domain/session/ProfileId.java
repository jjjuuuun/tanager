package tanager.domain.session;

import java.util.Objects;

/** 셸 프로파일 식별자. 저장된 창(pane)이 어떤 프로파일로 다시 뜰지 가리킨다. */
public record ProfileId(String value) {
    public ProfileId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("ProfileId 가 비어 있다");
        }
    }
}
