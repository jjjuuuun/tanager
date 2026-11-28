package tanager.adapter.toolkit.claude;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tanager.domain.shared.Result;
import tanager.domain.toolkit.Attribute;
import tanager.domain.toolkit.AttributeValue;
import tanager.domain.toolkit.ToolkitKind;

/**
 * 규칙 {@code .claude/rules/**}{@code /*.md}(하위 폴더까지). 이름은 {@code rules/} 아래 상대 경로({@code java/style.md}). 프런트매터
 * {@code paths} 가 있으면 경로 조건 로드, 없으면 항상 로드다. Claude Code 문서(memory, 2026-11-28 확인): 규칙에서 읽는 키는 {@code paths}
 * 하나이고 YAML 목록 또는 쉼표로 이은 글자다. 프런트매터가 깨지면 그것을 무시하고 {@code paths} 없는 규칙으로 읽는다 — 그래서 경고와 함께 항상
 * 로드 항목으로 보인다. 사용자 규칙과 프로젝트 규칙은 서로 가리지 않는다.
 */
final class RuleReader implements ClaudeReader {

    static final String ALWAYS = "always";
    static final String PATHS = "paths";

    private record RuleFile(Result<Frontmatter, FormatError> frontmatter, MarkdownText text) {
        static RuleFile read(InputStream raw) throws IOException {
            byte[] head = MarkdownText.head(raw);
            return new RuleFile(Frontmatter.read(new ByteArrayInputStream(head)), MarkdownText.of(head));
        }
    }

    @Override
    public void read(ClaudeLayout layout, Collector out) {
        for (ClaudeLayout.Root root : layout.roots()) {
            root.dir()
                    .child("rules")
                    .ifPresent(rules -> MarkdownTree.walk(
                            out,
                            rules,
                            (file, path) -> out.read(file, RuleFile::read).ifPresent(rule -> {
                                rule.frontmatter().error().ifPresent(error -> out.malformed(file, error));
                                out.item(ClaudeItems.item(
                                        ToolkitKind.RULE,
                                        root.scope(),
                                        String.join("/", path) + ".md",
                                        Optional.empty(),
                                        file,
                                        attributes(rule)));
                            })));
        }
    }

    private static List<Attribute> attributes(RuleFile rule) {
        Optional<String> paths =
                rule.frontmatter().value().flatMap(fm -> fm.text(PATHS, AttributeValue.Plain.MAX_CODE_POINTS));
        List<Attribute> attributes = new ArrayList<>();
        attributes.add(ClaudeItems.plain("load", paths.isPresent() ? PATHS : ALWAYS));
        paths.ifPresent(value -> attributes.add(ClaudeItems.plain(PATHS, value)));
        attributes.addAll(ClaudeItems.textAttributes(rule.text()));
        return List.copyOf(attributes);
    }
}
