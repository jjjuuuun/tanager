package tanager.adapter.toolkit.claude;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Pattern;
import tanager.domain.history.PastConversation;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 대화 기록(JSONL) 앞부분에서 얻는 두 값 — {@code cwd} 가 있는 첫 줄의 cwd 와 첫 사용자 메시지의 요약. 그 밖의 내용은 들고 있지 않는다(NFR-06).
 * 형식은 공개 사양이 아니라 실제 파일을 보고 정한 규칙이다(ADR-0020).
 */
record TranscriptHead(Optional<String> cwd, Optional<String> title) {

    /** 한 파일에서 읽는 최대 줄 수. */
    static final int MAX_LINES = 200;

    /** 한 파일에서 읽는 최대 바이트. 이 뒤로 걸친 줄은 잘려 JSON 이 깨지므로 버려진다. */
    static final int MAX_BYTES = 256 * 1024;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    // 명령 기록 · 알림 줄: "<command-name>…", "<local-command-stdout>…", "<task-notification>…" 처럼 여는 태그로 시작한다.
    private static final Pattern TAGGED = Pattern.compile("^\\s*<[a-z][a-z0-9_-]*>");

    /** 앞에서부터 한 줄씩 읽어 cwd 와 요약을 둘 다 찾거나 상한에 닿으면 멈춘다. 스트림은 닫는다. */
    static TranscriptHead read(InputStream raw) throws IOException {
        Optional<String> cwd = Optional.empty();
        Optional<String> title = Optional.empty();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new BoundedInputStream(raw, MAX_BYTES), StandardCharsets.UTF_8))) {
            for (int n = 0; n < MAX_LINES && (cwd.isEmpty() || title.isEmpty()); n++) {
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                Optional<JsonNode> node = parse(line);
                if (node.isEmpty()) {
                    continue;
                }
                if (cwd.isEmpty()) {
                    cwd = text(node.get().get("cwd"));
                }
                if (title.isEmpty()) {
                    title = prompt(node.get()).flatMap(PastConversation::summarize);
                }
            }
        }
        return new TranscriptHead(cwd, title);
    }

    private static Optional<JsonNode> parse(String line) {
        if (line.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode node = JSON.readTree(line);
            return node != null && node.isObject() ? Optional.of(node) : Optional.empty();
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }

    /**
     * 사용자가 직접 쓴 메시지의 원문. {@code type: "user"} 이고 {@code isMeta} 가 아니고 도구 결과({@code tool_result})가 아닌 줄이다.
     * {@code message.content} 는 문자열이거나 블록 배열이다(배열이면 text 블록을 이어 붙인다). 여는 태그로 시작하는 명령 기록은 건너뛴다.
     */
    private static Optional<String> prompt(JsonNode node) {
        if (!"user".equals(text(node.get("type")).orElse(""))) {
            return Optional.empty();
        }
        JsonNode meta = node.get("isMeta");
        if (meta != null && meta.isBoolean() && meta.booleanValue()) {
            return Optional.empty();
        }
        JsonNode message = node.get("message");
        if (message == null || !message.isObject()) {
            return Optional.empty();
        }
        Optional<String> body = content(message.get("content"));
        return body.filter(s -> !TAGGED.matcher(s).find());
    }

    private static Optional<String> content(JsonNode content) {
        if (content == null) {
            return Optional.empty();
        }
        if (content.isString()) {
            return Optional.of(content.stringValue());
        }
        if (!content.isArray()) {
            return Optional.empty();
        }
        StringBuilder joined = new StringBuilder();
        for (JsonNode block : content) {
            String type = text(block.get("type")).orElse("");
            if (type.equals("tool_result")) {
                return Optional.empty();
            }
            if (type.equals("text")) {
                text(block.get("text")).ifPresent(t -> joined.append(t).append(' '));
            }
        }
        return Optional.of(joined.toString());
    }

    private static Optional<String> text(JsonNode node) {
        return node != null && node.isString() ? Optional.of(node.stringValue()) : Optional.empty();
    }
}
