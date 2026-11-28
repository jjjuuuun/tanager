package tanager.adapter.toolkit.claude;

import java.util.List;
import tanager.domain.toolkit.ToolkitKind;

/**
 * 레거시 커맨드 {@code .claude/commands/**}{@code /*.md}. 이름은 파일 이름이고 하위 폴더는 {@code 폴더:이름}이다 — 커맨드 프런트매터는 {@code
 * name} 을 받지 않는다(Claude Code 문서).
 */
final class CommandReader implements ClaudeReader {

    @Override
    public void read(ClaudeLayout layout, Collector out) {
        for (ClaudeLayout.Root root : layout.roots()) {
            root.dir()
                    .child("commands")
                    .ifPresent(commands -> MarkdownTree.walk(
                            out,
                            commands,
                            (file, path) -> out.frontmatter(file)
                                    .ifPresent(fm -> out.item(ClaudeItems.item(
                                            ToolkitKind.COMMAND,
                                            root.scope(),
                                            String.join(":", path),
                                            fm,
                                            file,
                                            List.of("argument-hint"))))));
        }
    }
}
