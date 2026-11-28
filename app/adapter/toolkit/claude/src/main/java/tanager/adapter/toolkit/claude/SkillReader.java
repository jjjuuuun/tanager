package tanager.adapter.toolkit.claude;

import java.nio.file.Files;
import java.util.List;
import tanager.domain.toolkit.ToolkitKind;

/** 스킬 {@code .claude/skills/<이름>/SKILL.md}. 이름은 프런트매터 {@code name}, 없으면 폴더 이름. */
final class SkillReader implements ClaudeReader {

    private static final String FILE = "SKILL.md";

    @Override
    public void read(ClaudeLayout layout, Collector out) {
        for (ClaudeLayout.Root root : layout.roots()) {
            root.dir().child("skills").ifPresent(skills -> {
                for (Location dir : out.list(skills)) {
                    if (Files.isDirectory(dir.host()) && out.enter(dir)) {
                        dir.child(FILE)
                                .ifPresent(file -> out.frontmatter(file)
                                        .ifPresent(fm -> out.item(ClaudeItems.item(
                                                ToolkitKind.SKILL,
                                                root.scope(),
                                                ClaudeItems.declaredName(fm, dir.name()),
                                                fm,
                                                file,
                                                List.of()))));
                    }
                }
            });
        }
    }
}
