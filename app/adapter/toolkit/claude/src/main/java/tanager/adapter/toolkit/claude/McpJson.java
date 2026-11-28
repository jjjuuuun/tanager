package tanager.adapter.toolkit.claude;

import static tanager.adapter.toolkit.claude.StreamingJson.enterObject;
import static tanager.adapter.toolkit.claude.StreamingJson.keys;
import static tanager.adapter.toolkit.claude.StreamingJson.scalar;
import static tanager.adapter.toolkit.claude.StreamingJson.scalars;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tanager.domain.history.WorkspacePath;
import tanager.domain.shared.Result;
import tools.jackson.core.JsonParser;

/**
 * MCP 설정 JSON — {@code .mcp.json} · {@code managed-mcp.json} · {@code ~/.claude.json}(Claude Code 문서 mcp, 2026-11-28 확인).
 * 스트리밍으로 {@code mcpServers} 와 {@code projects[<이 경로>].mcpServers} 만 보고, 나머지 키(대화 기록 · 계정 정보 · 다른 프로젝트)는 값을
 * 만들지 않고 건너뛴다 — {@code ~/.claude.json} 은 수십 MB 이고 개인 정보가 많다. env · headers 의 값은 문자열로 만들지도 않는다.
 * 형식 오류 처리는 {@link StreamingJson} 이 맡는다.
 */
final class McpJson {

    /**
     * 한 파일의 서버들.
     *
     * @param servers 맨 위 {@code mcpServers}
     * @param local {@code projects[<이 경로>].mcpServers}(경로를 주었을 때만)
     */
    record Config(List<McpServer> servers, List<McpServer> local) {
        Config {
            servers = List.copyOf(servers);
            local = List.copyOf(local);
        }
    }

    private static final String MCP_SERVERS = "mcpServers";
    private static final String PROJECTS = "projects";

    private McpJson() {}

    /**
     * 스트림을 읽는다(닫는 것은 부르는 쪽). {@code project} 가 있으면 {@code projects} 의 키를 그 환경의 {@link WorkspacePath} 로 바꿔
     * 같은 경로(Windows 는 대소문자 · 구분자 무시)의 서버를 {@code local} 로 모은다.
     *
     * @throws IOException 읽기 실패(형식 오류가 아니다)
     */
    static Result<Config, FormatError> read(InputStream in, Optional<WorkspacePath> project) throws IOException {
        Objects.requireNonNull(project, "project");
        return StreamingJson.object(in, p -> {
            List<McpServer> servers = List.of();
            List<McpServer> local = new ArrayList<>();
            for (String name = p.nextName(); name != null; name = p.nextName()) {
                p.nextToken();
                if (name.equals(MCP_SERVERS)) {
                    servers = servers(p);
                } else if (name.equals(PROJECTS) && project.isPresent()) {
                    local.addAll(projects(p, project.get()));
                } else {
                    p.skipChildren();
                }
            }
            return new Config(servers, local);
        });
    }

    private static List<McpServer> servers(JsonParser p) {
        if (!enterObject(p)) {
            return List.of();
        }
        Map<String, McpServer> byName = new LinkedHashMap<>();
        for (String name = p.nextName(); name != null; name = p.nextName()) {
            p.nextToken();
            if (enterObject(p)) {
                server(name, p).ifPresent(server -> byName.put(server.name(), server));
            }
        }
        return List.copyOf(byName.values());
    }

    private static List<McpServer> projects(JsonParser p, WorkspacePath project) {
        List<McpServer> found = new ArrayList<>();
        if (!enterObject(p)) {
            return found;
        }
        for (String key = p.nextName(); key != null; key = p.nextName()) {
            p.nextToken();
            if (!sameProject(key, project)) {
                p.skipChildren();
            } else if (enterObject(p)) {
                for (String name = p.nextName(); name != null; name = p.nextName()) {
                    p.nextToken();
                    if (name.equals(MCP_SERVERS)) {
                        found.addAll(servers(p));
                    } else {
                        p.skipChildren();
                    }
                }
            }
        }
        return found;
    }

    private static boolean sameProject(String key, WorkspacePath project) {
        return WorkspacePath.of(project.env(), key)
                .value()
                .filter(project::equals)
                .isPresent();
    }

    private static Optional<McpServer> server(String name, JsonParser p) {
        Optional<String> type = Optional.empty();
        Optional<String> command = Optional.empty();
        Optional<String> url = Optional.empty();
        List<String> args = List.of();
        List<String> envKeys = List.of();
        List<String> headerKeys = List.of();
        boolean headersHelper = false;
        for (String field = p.nextName(); field != null; field = p.nextName()) {
            p.nextToken();
            switch (field) {
                case "type" -> type = scalar(p);
                case "command" -> command = scalar(p);
                case "args" -> args = scalars(p);
                case "url" -> url = scalar(p);
                case "env" -> envKeys = keys(p);
                case "headers" -> headerKeys = keys(p);
                case "headersHelper" -> {
                    headersHelper = true;
                    p.skipChildren();
                }
                default -> p.skipChildren();
            }
        }
        return McpServer.of(name, type, command, args, url, envKeys, headerKeys, headersHelper);
    }
}
