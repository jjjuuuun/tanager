package tanager.domain.toolkit;

import java.util.List;
import java.util.Optional;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;

/** 도구함 테스트의 공통 값. */
final class ToolkitFixtures {
    static final ShellEnvironment UBUNTU = new ShellEnvironment.Wsl("Ubuntu");
    static final ProviderId CLAUDE = new ProviderId("claude-code");
    static final ProviderId CODEX = new ProviderId("codex");
    static final WorkspacePath PROJECT_DIR = path("/home/me/proj");
    static final SourceRef SOURCE = new SourceRef(path("/home/me/proj/.claude/skills/tdd/SKILL.md"));

    private ToolkitFixtures() {}

    static WorkspacePath path(String raw) {
        return WorkspacePath.of(UBUNTU, raw).value().orElseThrow();
    }

    static ToolkitItem item(ProviderId provider, ToolkitKind kind, String name, ToolkitScope scope) {
        return new ToolkitItem(provider, kind, name, scope, Optional.empty(), SOURCE, List.of(), false);
    }

    static ToolkitItem item(ToolkitKind kind, String name, ToolkitScope scope) {
        return item(CLAUDE, kind, name, scope);
    }
}
