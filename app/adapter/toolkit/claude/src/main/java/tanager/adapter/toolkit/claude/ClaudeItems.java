package tanager.adapter.toolkit.claude;

import java.util.ArrayList;
import java.util.List;
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
            fm.text(key, AttributeValue.Plain.MAX_CODE_POINTS)
                    .ifPresent(value -> attributes.add(new Attribute(key, new AttributeValue.Plain(value))));
        }
        return new ToolkitItem(
                ClaudeToolkitProvider.ID,
                kind,
                Frontmatter.clean(name, ToolkitItem.MAX_NAME_CODE_POINTS).orElse(file.name()),
                scope,
                fm.text("description", ToolkitItem.MAX_DESCRIPTION_CODE_POINTS),
                new SourceRef(file.env()),
                attributes,
                false);
    }
}
