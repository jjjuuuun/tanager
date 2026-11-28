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
        layout.managed()
                .flatMap(dir -> dir.child(MEMORY))
                .ifPresent(file -> memory(out, ToolkitScope.MANAGED, MEMORY, Optional.empty(), file));
        layout.base().ifPresent(base -> {
            base.child(MEMORY).ifPresent(file -> memory(out, ToolkitScope.PROJECT, MEMORY, Optional.empty(), file));
            base.child(".claude")
                    .flatMap(dir -> dir.child(MEMORY))
                    .ifPresent(file -> memory(out, ToolkitScope.PROJECT, ".claude/" + MEMORY, Optional.empty(), file));
            base.child(LOCAL).ifPresent(file -> memory(out, ToolkitScope.LOCAL, LOCAL, Optional.empty(), file));
        });
        for (Location dir : layout.ancestors()) {
            Optional<String> where = Optional.of(dir.env().path());
            dir.child(MEMORY).ifPresent(file -> memory(out, ToolkitScope.PARENT, MEMORY, where, file));
            dir.child(LOCAL).ifPresent(file -> memory(out, ToolkitScope.PARENT, LOCAL, where, file));
        }
        for (ClaudeLayout.Root root : layout.roots()) {
            if (root.scope() == ToolkitScope.USER) {
                root.dir()
                        .child(MEMORY)
                        .ifPresent(file -> memory(out, ToolkitScope.USER, MEMORY, Optional.empty(), file));
            }
        }
    }

    private static void memory(
            Collector out, ToolkitScope scope, String name, Optional<String> description, Location file) {
        out.read(file, MarkdownText::read)
                .ifPresent(text -> out.item(ClaudeItems.item(
                        ToolkitKind.MEMORY, scope, name, description, file, ClaudeItems.textAttributes(text))));
    }
}
