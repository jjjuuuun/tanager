package tanager.domain.toolkit;

import java.util.Objects;

/** 항목의 속성 하나(예 MCP 서버의 {@code command}). 불변식: key 는 비어 있지 않고 제어 문자가 없다. */
public record Attribute(String key, AttributeValue value) {
    public Attribute {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        if (key.isBlank() || Texts.hasControl(key)) {
            throw new IllegalArgumentException("속성 key 가 비었거나 제어 문자가 있다");
        }
    }
}
