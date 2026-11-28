package tanager.adapter.toolkit.claude;

import java.util.List;
import java.util.Optional;
import tanager.domain.history.WorkspacePath;
import tanager.domain.toolkit.ToolkitKind;
import tanager.domain.toolkit.ToolkitScope;

/**
 * MCP 서버(Claude Code 문서 mcp · managed-mcp, 2026-11-28 확인). 관리형 {@code managed-mcp.json} → 로컬 {@code
 * ~/.claude.json} 의 {@code projects[<경로>].mcpServers} → 프로젝트 {@code <경로>/.mcp.json} → 사용자 {@code ~/.claude.json} 의
 * {@code mcpServers}. 비밀값은 {@link McpJson} 이 읽는 자리에서 버린다. 읽히는 {@code managed-mcp.json} 이 있으면 다른 범위의 서버는 모두
 * 가려진다(독점).
 */
final class McpReader implements ClaudeReader {

    static final String PROJECT_FILE = ".mcp.json";
    static final String USER_FILE = ".claude.json";
    static final String MANAGED_FILE = "managed-mcp.json";

    private record Source(Location file, McpJson.Config config) {}

    @Override
    public void read(ClaudeLayout layout, Collector out) {
        Optional<Source> managed = layout.managed().flatMap(dir -> config(out, dir, MANAGED_FILE, Optional.empty()));
        Optional<Source> home =
                layout.userHome().flatMap(dir -> config(out, dir, USER_FILE, Optional.of(layout.project())));
        Optional<Source> project = layout.base().flatMap(dir -> config(out, dir, PROJECT_FILE, Optional.empty()));
        managed.ifPresent(source -> {
            // 서버가 하나도 없는 managed-mcp.json 도 독점이다(managed-mcp 문서 "Disable MCP")
            out.exclusive(ToolkitKind.MCP_SERVER);
            items(out, ToolkitScope.MANAGED, source.file(), source.config().servers());
        });
        home.ifPresent(source ->
                items(out, ToolkitScope.LOCAL, source.file(), source.config().local()));
        project.ifPresent(source ->
                items(out, ToolkitScope.PROJECT, source.file(), source.config().servers()));
        home.ifPresent(source ->
                items(out, ToolkitScope.USER, source.file(), source.config().servers()));
    }

    private static Optional<Source> config(
            Collector out, Location dir, String fileName, Optional<WorkspacePath> project) {
        return dir.child(fileName)
                .flatMap(file -> out.read(file, in -> McpJson.read(in, project)).flatMap(read -> {
                    read.error().ifPresent(error -> out.malformed(file, error));
                    return read.value().map(config -> new Source(file, config));
                }));
    }

    private static void items(Collector out, ToolkitScope scope, Location file, List<McpServer> servers) {
        for (McpServer server : servers) {
            out.item(ClaudeItems.item(
                    ToolkitKind.MCP_SERVER, scope, server.name(), Optional.empty(), file, server.attributes()));
        }
    }
}
