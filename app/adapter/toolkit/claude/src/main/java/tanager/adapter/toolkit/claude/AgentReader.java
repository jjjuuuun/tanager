package tanager.adapter.toolkit.claude;

import java.util.List;
import tanager.domain.toolkit.ToolkitKind;

/**
 * 서브에이전트 {@code .claude/agents/**}{@code /*.md}(Claude Code 는 하위 폴더까지 읽는다). 이름은 프런트매터 {@code name}, 없으면 파일
 * 이름. 속성은 {@code model} · {@code tools}.
 */
final class AgentReader implements ClaudeReader {

    @Override
    public void read(ClaudeLayout layout, Collector out) {
        for (ClaudeLayout.Root root : layout.roots()) {
            root.dir()
                    .child("agents")
                    .ifPresent(agents -> MarkdownTree.walk(
                            out,
                            agents,
                            (file, path) -> out.frontmatter(file)
                                    .ifPresent(fm -> out.item(ClaudeItems.item(
                                            ToolkitKind.SUBAGENT,
                                            root.scope(),
                                            ClaudeItems.declaredName(fm, path.getLast()),
                                            fm,
                                            file,
                                            List.of("model", "tools"))))));
        }
    }
}
