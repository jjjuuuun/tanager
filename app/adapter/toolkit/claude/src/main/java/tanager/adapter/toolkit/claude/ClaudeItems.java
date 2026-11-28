package tanager.adapter.toolkit.claude;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import tanager.domain.toolkit.Attribute;
import tanager.domain.toolkit.AttributeValue;
import tanager.domain.toolkit.SourceRef;
import tanager.domain.toolkit.ToolkitItem;
import tanager.domain.toolkit.ToolkitKind;
import tanager.domain.toolkit.ToolkitScope;

/** 프런트매터 → 도구함 항목. 글자는 도메인 상한에 맞춰 한 줄로 펴고 자른다. */
final class ClaudeItems {

    private ClaudeItems() {}

    /** 프런트매터 {@code name}, 없으면 {@code fallback}(폴더 · 파일 이름). */
    static String declaredName(Frontmatter fm, String fallback) {
        return fm.text("name", ToolkitItem.MAX_NAME_CODE_POINTS).orElse(fallback);
    }

    /**
     * @param name 항목 이름(한 줄로 펴고 상한에 맞춰 자른다)
     * @param attributeKeys 속성으로 실을 프런트매터 키(이 순서로, 값이 있는 것만)
     */
    static ToolkitItem item(
            ToolkitKind kind,
            ToolkitScope scope,
            String name,
            Frontmatter fm,
            Location file,
            List<String> attributeKeys) {
        List<Attribute> attributes = new ArrayList<>();
        for (String key : attributeKeys) {
            fm.text(key, AttributeValue.Plain.MAX_CODE_POINTS).ifPresent(value -> attributes.add(plain(key, value)));
        }
        return item(
                kind, scope, name, fm.text("description", ToolkitItem.MAX_DESCRIPTION_CODE_POINTS), file, attributes);
    }

    /**
     * @param name 항목 이름(한 줄로 펴고 상한에 맞춰 자른다)
     * @param description 설명(상한을 넘으면 자른다)
     */
    static ToolkitItem item(
            ToolkitKind kind,
            ToolkitScope scope,
            String name,
            Optional<String> description,
            Location file,
            List<Attribute> attributes) {
        return new ToolkitItem(
                ClaudeToolkitProvider.ID,
                kind,
                Frontmatter.clean(name, ToolkitItem.MAX_NAME_CODE_POINTS).orElse(file.name()),
                scope,
                description.flatMap(text -> Frontmatter.clean(text, ToolkitItem.MAX_DESCRIPTION_CODE_POINTS)),
                new SourceRef(file.env()),
                attributes,
                false);
    }

    /** 메모리 · 규칙 본문의 속성 — {@code lines}, {@code heading}(있으면), {@code imports}(0 이 아니면). */
    static List<Attribute> textAttributes(MarkdownText text) {
        List<Attribute> attributes = new ArrayList<>();
        attributes.add(plain("lines", text.lines()));
        text.heading().ifPresent(heading -> attributes.add(plain("heading", heading)));
        if (text.imports() > 0) {
            attributes.add(plain("imports", Integer.toString(text.imports())));
        }
        return List.copyOf(attributes);
    }

    static Attribute plain(String key, String value) {
        return new Attribute(key, new AttributeValue.Plain(value));
    }

    /** 값이 없는 속성 — 키만 보인다. */
    static Attribute masked(String key) {
        return new Attribute(key, new AttributeValue.Masked());
    }

    /** 같은 키는 앞의 것만 둔다 — 펴서 같아진 env · headers 키({@code "A\nB"} 와 {@code "A B"}) 등. 도메인 불변식: key 유일. */
    static List<Attribute> unique(List<Attribute> attributes) {
        Set<String> seen = new HashSet<>();
        List<Attribute> out = new ArrayList<>();
        for (Attribute attribute : attributes) {
            if (seen.add(attribute.key())) {
                out.add(attribute);
            }
        }
        return List.copyOf(out);
    }
}
