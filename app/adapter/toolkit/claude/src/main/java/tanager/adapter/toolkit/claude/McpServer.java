package tanager.adapter.toolkit.claude;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tanager.domain.toolkit.Attribute;
import tanager.domain.toolkit.AttributeValue;
import tanager.domain.toolkit.ToolkitItem;

/**
 * MCP 서버 정의 하나 — 비밀값을 이미 버린 모양이다({@link SecretMask}). 원문 명령 · URL · env · headers 값을 담지 않으므로 {@code
 * toString} 에도 남지 않는다.
 */
record McpServer(String name, List<Attribute> attributes) {

    /**
     * 항목이 없는 {@code type} 은 stdio 다 — Claude Code 문서(2026-11-28 확인,
     * https://code.claude.com/docs/en/mcp#option-1-add-a-remote-http-server): "Claude Code reads an entry with no
     * {@code type} as a stdio server".
     */
    static final String DEFAULT_TYPE = "stdio";

    McpServer {
        Objects.requireNonNull(name, "name");
        attributes = List.copyOf(attributes);
    }

    /**
     * 읽은 필드로 만든다. 이름이 비면 empty.
     *
     * @param envKeys {@code env} 의 키(값은 읽지 않았다)
     * @param headerKeys {@code headers} 의 키(값은 읽지 않았다)
     * @param headersHelper {@code headersHelper} 가 있었는가(셸 명령이라 토큰이 들 수 있어 값 전체를 가린다)
     */
    static Optional<McpServer> of(
            String name,
            Optional<String> type,
            Optional<String> command,
            List<String> args,
            Optional<String> url,
            List<String> envKeys,
            List<String> headerKeys,
            boolean headersHelper) {
        Optional<String> cleanName = Frontmatter.clean(name, ToolkitItem.MAX_NAME_CODE_POINTS);
        if (cleanName.isEmpty()) {
            return Optional.empty();
        }
        List<Attribute> attributes = new ArrayList<>();
        attributes.add(ClaudeItems.plain("type", type.flatMap(McpServer::clean).orElse(DEFAULT_TYPE)));
        command.map(c -> SecretMask.commandLine(c, args))
                .flatMap(McpServer::clean)
                .ifPresent(line -> attributes.add(ClaudeItems.plain("command", line)));
        url.ifPresent(raw -> attributes.add(SecretMask.origin(raw)
                .map(origin -> ClaudeItems.plain("url", origin))
                .orElseGet(() -> ClaudeItems.masked("url"))));
        if (headersHelper) {
            attributes.add(ClaudeItems.masked("headersHelper"));
        }
        attributes.addAll(SecretMask.keysOnly("env", envKeys));
        attributes.addAll(SecretMask.keysOnly("headers", headerKeys));
        return Optional.of(new McpServer(cleanName.get(), ClaudeItems.unique(attributes)));
    }

    private static Optional<String> clean(String text) {
        return Frontmatter.clean(text, AttributeValue.Plain.MAX_CODE_POINTS);
    }
}
