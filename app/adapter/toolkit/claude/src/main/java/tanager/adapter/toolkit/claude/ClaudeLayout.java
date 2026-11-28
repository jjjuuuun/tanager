package tanager.adapter.toolkit.claude;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import tanager.domain.history.WorkspacePath;
import tanager.domain.toolkit.ToolkitScope;

/**
 * Claude Code 구성 파일이 있는 곳(SDD §7.1). 경로 규칙은 Claude Code 판마다 바뀌므로 여기에만 둔다.
 *
 * @param project 고른 폴더(그 환경의 경로)
 * @param home 그 환경의 홈. 모르면 사용자 범위를 읽지 않는다
 * @param host 환경 경로 → 앱이 읽을 경로
 */
record ClaudeLayout(WorkspacePath project, Optional<WorkspacePath> home, Function<WorkspacePath, Optional<Path>> host) {

    /** 한 범위의 {@code .claude/} 폴더. */
    record Root(ToolkitScope scope, Location dir) {}

    ClaudeLayout {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(host, "host");
    }

    /** 앱이 프로젝트 폴더를 열 수 있는가. */
    boolean reachable() {
        return host.apply(project).isPresent();
    }

    /** 범위마다의 {@code .claude/} — 프로젝트, 사용자 순. 앱이 열 수 없는 쪽은 뺀다. */
    List<Root> roots() {
        List<Root> roots = new ArrayList<>();
        claudeDir(project).ifPresent(dir -> roots.add(new Root(ToolkitScope.PROJECT, dir)));
        home.flatMap(this::claudeDir).ifPresent(dir -> roots.add(new Root(ToolkitScope.USER, dir)));
        return List.copyOf(roots);
    }

    private Optional<Location> claudeDir(WorkspacePath base) {
        return host.apply(base).flatMap(hostBase -> new Location(base, hostBase).child(".claude"));
    }
}
