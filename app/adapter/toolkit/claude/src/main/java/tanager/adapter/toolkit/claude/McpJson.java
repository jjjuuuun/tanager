package tanager.adapter.toolkit.claude;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import tanager.domain.history.WorkspacePath;
import tanager.domain.shared.Result;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.databind.json.JsonMapper;

/**
 * MCP 설정 JSON — {@code .mcp.json} · {@code managed-mcp.json} · {@code ~/.claude.json}(Claude Code 문서 mcp, 2026-11-28 확인).
 * 스트리밍으로 {@code mcpServers} 와 {@code projects[<이 경로>].mcpServers} 만 보고, 나머지 키(대화 기록 · 계정 정보 · 다른 프로젝트)는 값을
 * 만들지 않고 건너뛴다 — {@code ~/.claude.json} 은 수십 MB 이고 개인 정보가 많다. env · headers 의 값은 문자열로 만들지도 않는다.
 *
 * <p>형식 오류는 줄 번호만 돌려준다. Jackson 예외 메시지에는 원문 조각(비밀값일 수 있다)이 들어 있어 어디에도 쓰지 않는다.
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

    private static final JsonMapper JSON = JsonMapper.builder().build();
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
        try (JsonParser p = JSON.createParser(in)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                return Result.err(new FormatError(lineOf(p.currentTokenLocation())));
            }
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
            return Result.ok(new Config(servers, local));
        } catch (JacksonIOException e) {
            throw e.getCause();
        } catch (JacksonException e) {
            return Result.err(new FormatError(lineOf(e.getLocation())));
        }
    }

    private static List<McpServer> servers(JsonParser p) {
        if (p.currentToken() != JsonToken.START_OBJECT) {
            p.skipChildren();
            return List.of();
        }
        Map<String, McpServer> byName = new LinkedHashMap<>();
        for (String name = p.nextName(); name != null; name = p.nextName()) {
            if (p.nextToken() == JsonToken.START_OBJECT) {
                server(name, p).ifPresent(server -> byName.put(server.name(), server));
            } else {
                p.skipChildren();
            }
        }
        return List.copyOf(byName.values());
    }

    private static List<McpServer> projects(JsonParser p, WorkspacePath project) {
        if (p.currentToken() != JsonToken.START_OBJECT) {
            p.skipChildren();
            return List.of();
        }
        List<McpServer> found = new ArrayList<>();
        for (String key = p.nextName(); key != null; key = p.nextName()) {
            if (p.nextToken() == JsonToken.START_OBJECT && sameProject(key, project)) {
                for (String name = p.nextName(); name != null; name = p.nextName()) {
                    p.nextToken();
                    if (name.equals(MCP_SERVERS)) {
                        found.addAll(servers(p));
                    } else {
                        p.skipChildren();
                    }
                }
            } else {
                p.skipChildren();
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

    private static Optional<String> scalar(JsonParser p) {
        JsonToken token = p.currentToken();
        if (token.isScalarValue() && token != JsonToken.VALUE_NULL) {
            return Optional.of(p.getValueAsString());
        }
        p.skipChildren();
        return Optional.empty();
    }

    private static List<String> scalars(JsonParser p) {
        if (p.currentToken() != JsonToken.START_ARRAY) {
            p.skipChildren();
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonToken token = p.nextToken(); token != null && token != JsonToken.END_ARRAY; token = p.nextToken()) {
            scalar(p).ifPresent(values::add);
        }
        return values;
    }

    /** 객체의 키만. 값은 건너뛴다 — 파서가 값의 글자를 문자열로 만들지 않는다. */
    private static List<String> keys(JsonParser p) {
        if (p.currentToken() != JsonToken.START_OBJECT) {
            p.skipChildren();
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        for (String key = p.nextName(); key != null; key = p.nextName()) {
            p.nextToken();
            p.skipChildren();
            keys.add(key);
        }
        return keys;
    }

    private static OptionalInt lineOf(TokenStreamLocation location) {
        if (location == null || location.getLineNr() < 1) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(location.getLineNr());
    }
}
