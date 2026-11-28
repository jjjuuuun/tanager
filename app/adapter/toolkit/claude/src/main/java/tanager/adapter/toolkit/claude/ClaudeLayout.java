package tanager.adapter.toolkit.claude;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import tanager.domain.history.ShellEnvironment;
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

    /**
     * 위로 올라가며 메모리를 찾을 최대 단계. {@code \\wsl.localhost} 에서는 단계마다 파일 시각을 묻는 왕복이 생긴다(#211).
     */
    static final int MAX_ANCESTORS = 32;

    /**
     * 관리형(조직 배포) 폴더 — Claude Code 문서(2026-11-28 확인, https://code.claude.com/docs/en/memory#deploy-organization-wide-claude-md
     * · https://code.claude.com/docs/en/managed-settings): Linux · WSL {@code /etc/claude-code}, Windows {@code C:\Program
     * Files\ClaudeCode}. 옛 Windows 위치 {@code C:\ProgramData\ClaudeCode} 는 Claude Code 가 더는 읽지 않는다.
     */
    private static final String MANAGED_WSL = "/etc/claude-code";

    private static final String MANAGED_WINDOWS = "C:\\Program Files\\ClaudeCode";

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

    /** 그 환경의 홈 자신({@code ~/.claude.json} 자리). 모르거나 앱이 열 수 없으면 empty. */
    Optional<Location> userHome() {
        return home.flatMap(this::locate);
    }

    /** 고른 폴더 자신. */
    Optional<Location> base() {
        return locate(project);
    }

    /** 고른 폴더의 부모부터 루트 쪽으로(가까운 순) 최대 {@link #MAX_ANCESTORS} 개. 앱이 열 수 없는 쪽은 뺀다. */
    List<Location> ancestors() {
        List<Location> ancestors = new ArrayList<>();
        Optional<WorkspacePath> current = project.parent();
        for (int step = 0; step < MAX_ANCESTORS && current.isPresent(); step++) {
            locate(current.get()).ifPresent(ancestors::add);
            current = current.get().parent();
        }
        return List.copyOf(ancestors);
    }

    /** 그 환경의 관리형 폴더. 앱이 열 수 없으면 empty. */
    Optional<Location> managed() {
        String raw = switch (project.env()) {
            case ShellEnvironment.Wsl _ -> MANAGED_WSL;
            case ShellEnvironment.Windows _ -> MANAGED_WINDOWS;
        };
        return WorkspacePath.of(project.env(), raw).value().flatMap(this::locate);
    }

    private Optional<Location> claudeDir(WorkspacePath base) {
        return locate(base).flatMap(dir -> dir.child(".claude"));
    }

    private Optional<Location> locate(WorkspacePath path) {
        return host.apply(path).map(hostPath -> new Location(path, hostPath));
    }
}
