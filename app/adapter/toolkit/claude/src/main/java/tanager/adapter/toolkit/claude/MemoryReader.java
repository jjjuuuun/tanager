package tanager.adapter.toolkit.claude;

import java.util.Optional;
import tanager.domain.toolkit.ToolkitKind;
import tanager.domain.toolkit.ToolkitScope;

/**
 * 메모리 {@code CLAUDE.md} · {@code CLAUDE.local.md}(Claude Code 문서 memory, 2026-11-28 확인). 관리형 → 프로젝트({@code CLAUDE.md} ·
 * {@code .claude/CLAUDE.md}) → 로컬({@code CLAUDE.local.md}) → 상위 디렉터리(가까운 순, 두 이름 모두) → 사용자({@code
 * ~/.claude/CLAUDE.md}). 모두 합쳐 적용되므로 가리지 않는다. {@code @import} 는 세기만 한다.
 */
final class MemoryReader implements ClaudeReader {

    static final String MEMORY = "CLAUDE.md";
    static final String LOCAL = "CLAUDE.local.md";

    @Override
    public void read(ClaudeLayout layout, Collector out) {
        layout.managed().ifPresent(dir -> memory(out, ToolkitScope.MANAGED, dir, MEMORY, MEMORY, Optional.empty()));
        layout.base().ifPresent(base -> {
            memory(out, ToolkitScope.PROJECT, base, MEMORY, MEMORY, Optional.empty());
            memory(out, ToolkitScope.LOCAL, base, LOCAL, LOCAL, Optional.empty());
        });
        for (ClaudeLayout.Root root : layout.roots()) {
            // 프로젝트의 .claude/CLAUDE.md 는 같은 범위의 CLAUDE.md 와 나란히 보이므로 폴더 이름을 붙인다
            String name = root.scope() == ToolkitScope.PROJECT ? ".claude/" + MEMORY : MEMORY;
            memory(out, root.scope(), root.dir(), MEMORY, name, Optional.empty());
        }
        for (Location dir : layout.ancestors()) {
            Optional<String> where = Optional.of(dir.env().path());
            memory(out, ToolkitScope.PARENT, dir, MEMORY, MEMORY, where);
            memory(out, ToolkitScope.PARENT, dir, LOCAL, LOCAL, where);
        }
    }

    private static void memory(
            Collector out,
            ToolkitScope scope,
            Location dir,
            String fileName,
            String name,
            Optional<String> description) {
        dir.child(fileName)
                .ifPresent(file -> out.read(file, MarkdownText::read)
                        .ifPresent(text -> out.item(ClaudeItems.item(
                                ToolkitKind.MEMORY,
                                scope,
                                name,
                                description,
                                file,
                                ClaudeItems.textAttributes(text)))));
    }
}
