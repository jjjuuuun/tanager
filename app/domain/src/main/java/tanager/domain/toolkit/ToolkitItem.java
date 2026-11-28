package tanager.domain.toolkit;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 도구함 항목 하나(FR-TK-02 · FR-TK-05). {@code shadowed} 는 같은 공급자 · 종류 · 이름의 항목이 더 높은 범위에 있어 이 항목이 적용되지
 * 않는다는 뜻이다({@link Shadowing}).
 *
 * <p>불변식: name 은 앞뒤 공백을 벗긴 뒤 1~200 코드 포인트 · 제어 문자 없음, description 은 1000 코드 포인트 이하, attributes 의 key 는
 * 유일하다(순서 유지).
 */
public record ToolkitItem(
        ProviderId provider,
        ToolkitKind kind,
        String name,
        ToolkitScope scope,
        Optional<String> description,
        SourceRef source,
        List<Attribute> attributes,
        boolean shadowed) {

    /** 이름의 최대 길이(코드 포인트). */
    public static final int MAX_NAME_CODE_POINTS = 200;

    /** 설명의 최대 길이(코드 포인트). */
    public static final int MAX_DESCRIPTION_CODE_POINTS = 1000;

    public ToolkitItem {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(attributes, "attributes");
        name = name.strip();
        if (name.isEmpty() || Texts.codePoints(name) > MAX_NAME_CODE_POINTS || Texts.hasControl(name)) {
            throw new IllegalArgumentException("항목 이름은 1~" + MAX_NAME_CODE_POINTS + " 코드 포인트, 제어 문자 없이");
        }
        if (description
                .filter(d -> Texts.codePoints(d) > MAX_DESCRIPTION_CODE_POINTS)
                .isPresent()) {
            throw new IllegalArgumentException("항목 설명이 " + MAX_DESCRIPTION_CODE_POINTS + " 코드 포인트를 넘는다");
        }
        attributes = List.copyOf(attributes);
        Set<String> keys = new HashSet<>();
        for (Attribute attribute : attributes) {
            if (!keys.add(attribute.key())) {
                throw new IllegalArgumentException("속성 key 가 겹친다: " + attribute.key());
            }
        }
    }

    /** {@code shadowed} 만 바꾼 항목. */
    public ToolkitItem withShadowed(boolean shadowed) {
        return new ToolkitItem(provider, kind, name, scope, description, source, attributes, shadowed);
    }
}
