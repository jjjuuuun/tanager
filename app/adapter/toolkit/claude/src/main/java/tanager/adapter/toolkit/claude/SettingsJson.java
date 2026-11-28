package tanager.adapter.toolkit.claude;

import static tanager.adapter.toolkit.claude.StreamingJson.enterArray;
import static tanager.adapter.toolkit.claude.StreamingJson.enterObject;
import static tanager.adapter.toolkit.claude.StreamingJson.keys;
import static tanager.adapter.toolkit.claude.StreamingJson.nextElement;
import static tanager.adapter.toolkit.claude.StreamingJson.number;
import static tanager.adapter.toolkit.claude.StreamingJson.string;
import static tanager.adapter.toolkit.claude.StreamingJson.strings;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tanager.domain.shared.Result;
import tanager.domain.toolkit.Attribute;
import tanager.domain.toolkit.AttributeValue;
import tanager.domain.toolkit.ToolkitItem;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;

/**
 * Claude Code 설정 JSON — {@code settings.json} · {@code settings.local.json} · {@code managed-settings.json}(Claude Code 문서
 * settings · hooks · permissions, 2026-11-28 확인). 스트리밍으로 {@code hooks} 와 {@code permissions} 만 보고 나머지 키({@code env}
 * · {@code apiKeyHelper} 등 — 비밀값이 많다)는 값을 만들지 않고 건너뛴다.
 *
 * <ul>
 *   <li>훅 — {@code hooks.<이벤트>[{matcher, hooks: [{type, command, args, url, headers, server, tool, timeout}]}]}:
 *       https://code.claude.com/docs/en/hooks#configuration. 훅 하나 = 항목 하나, 이름 = {@code 이벤트 · matcher}(matcher 가 없으면
 *       이벤트만). {@code command} 는 {@link SecretMask#commandLine} 을 거쳐 {@link #MAX_COMMAND_CODE_POINTS} 로 자른다. {@code
 *       headers} 는 키만, {@code prompt} · {@code input} 은 읽지 않는다.
 *   <li>권한 — {@code permissions.allow} · {@code ask} · {@code deny} 의 규칙 하나 = 항목 하나(속성 {@code list}), {@code
 *       defaultMode} · {@code additionalDirectories} 는 {@link #DEFAULTS_NAME} 항목 하나:
 *       https://code.claude.com/docs/en/permissions
 * </ul>
 */
final class SettingsJson {

    /** 훅 명령을 보여 줄 최대 길이(코드 포인트, 가린 뒤 자른다). */
    static final int MAX_COMMAND_CODE_POINTS = 80;

    /** {@code defaultMode} · {@code additionalDirectories} 를 담는 권한 항목의 이름 — 설정 키 그대로(화면 문장이 아니다). */
    static final String DEFAULTS_NAME = "permissions";

    /** 이벤트와 matcher 사이. */
    static final String SEPARATOR = " · ";

    private static final List<String> RULE_LISTS = List.of("allow", "ask", "deny");

    /** 항목 하나가 될 것 — 이름과 이미 가린 속성. */
    record Entry(String name, List<Attribute> attributes) {
        Entry {
            Objects.requireNonNull(name, "name");
            attributes = List.copyOf(attributes);
        }
    }

    /** 한 파일의 훅 · 권한(파일 순서). */
    record Settings(List<Entry> hooks, List<Entry> permissions) {
        Settings {
            hooks = List.copyOf(hooks);
            permissions = List.copyOf(permissions);
        }
    }

    private SettingsJson() {}

    /**
     * 스트림을 읽는다(닫는 것은 부르는 쪽).
     *
     * @throws IOException 읽기 실패(형식 오류가 아니다)
     */
    static Result<Settings, FormatError> read(InputStream in) throws IOException {
        return StreamingJson.object(in, p -> {
            List<Entry> hooks = new ArrayList<>();
            List<Entry> permissions = new ArrayList<>();
            for (String name = p.nextName(); name != null; name = p.nextName()) {
                p.nextToken();
                switch (name) {
                    case "hooks" -> hooks.addAll(hooks(p));
                    case "permissions" -> permissions.addAll(permissions(p));
                    default -> p.skipChildren();
                }
            }
            return new Settings(hooks, permissions);
        });
    }

    private static List<Entry> hooks(JsonParser p) {
        List<Entry> entries = new ArrayList<>();
        if (!enterObject(p)) {
            return entries;
        }
        for (String event = p.nextName(); event != null; event = p.nextName()) {
            p.nextToken();
            if (!enterArray(p)) {
                continue;
            }
            for (Optional<JsonToken> token = nextElement(p); token.isPresent(); token = nextElement(p)) {
                if (enterObject(p)) {
                    entries.addAll(group(event, p));
                }
            }
        }
        return entries;
    }

    private static List<Entry> group(String event, JsonParser p) {
        Optional<String> matcher = Optional.empty();
        List<List<Attribute>> handlers = new ArrayList<>();
        for (String field = p.nextName(); field != null; field = p.nextName()) {
            p.nextToken();
            switch (field) {
                case "matcher" -> matcher = string(p).filter(m -> !m.isBlank());
                case "hooks" -> handlers.addAll(handlers(p));
                default -> p.skipChildren();
            }
        }
        Optional<String> eventName = Frontmatter.clean(event, ToolkitItem.MAX_NAME_CODE_POINTS);
        if (eventName.isEmpty()) {
            return List.of();
        }
        String name = matcher.map(m -> eventName.get() + SEPARATOR + m).orElse(eventName.get());
        List<Entry> entries = new ArrayList<>();
        for (List<Attribute> attributes : handlers) {
            entries.add(new Entry(name, attributes));
        }
        return entries;
    }

    private static List<List<Attribute>> handlers(JsonParser p) {
        List<List<Attribute>> handlers = new ArrayList<>();
        if (!enterArray(p)) {
            return handlers;
        }
        for (Optional<JsonToken> token = nextElement(p); token.isPresent(); token = nextElement(p)) {
            if (enterObject(p)) {
                List<Attribute> attributes = handler(p);
                if (!attributes.isEmpty()) {
                    handlers.add(attributes);
                }
            }
        }
        return handlers;
    }

    private static List<Attribute> handler(JsonParser p) {
        Optional<String> type = Optional.empty();
        Optional<String> command = Optional.empty();
        List<String> args = List.of();
        Optional<String> url = Optional.empty();
        List<String> headerKeys = List.of();
        Optional<String> server = Optional.empty();
        Optional<String> tool = Optional.empty();
        Optional<String> timeout = Optional.empty();
        for (String field = p.nextName(); field != null; field = p.nextName()) {
            p.nextToken();
            switch (field) {
                case "type" -> type = string(p);
                case "command" -> command = string(p);
                case "args" -> args = strings(p);
                case "url" -> url = string(p);
                case "headers" -> headerKeys = keys(p);
                case "server" -> server = string(p);
                case "tool" -> tool = string(p);
                case "timeout" -> timeout = number(p);
                default -> p.skipChildren();
            }
        }
        List<String> argv = args;
        List<Attribute> attributes = new ArrayList<>();
        plain(attributes, "type", type);
        command.map(c -> SecretMask.commandLine(c, argv))
                .flatMap(line -> Frontmatter.clean(line, MAX_COMMAND_CODE_POINTS))
                .ifPresent(line -> attributes.add(ClaudeItems.plain("command", line)));
        url.ifPresent(raw -> attributes.add(SecretMask.origin(raw)
                .map(origin -> ClaudeItems.plain("url", origin))
                .orElseGet(() -> ClaudeItems.masked("url"))));
        attributes.addAll(SecretMask.keysOnly("headers", headerKeys));
        plain(attributes, "server", server);
        plain(attributes, "tool", tool);
        plain(attributes, "timeout", timeout);
        return ClaudeItems.unique(attributes);
    }

    private static List<Entry> permissions(JsonParser p) {
        List<Entry> rules = new ArrayList<>();
        if (!enterObject(p)) {
            return rules;
        }
        Optional<String> defaultMode = Optional.empty();
        List<String> directories = List.of();
        for (String field = p.nextName(); field != null; field = p.nextName()) {
            p.nextToken();
            if (RULE_LISTS.contains(field)) {
                List<Attribute> list = List.of(ClaudeItems.plain("list", field));
                for (String rule : strings(p)) {
                    Frontmatter.clean(SecretMask.redact(rule), ToolkitItem.MAX_NAME_CODE_POINTS)
                            .ifPresent(name -> rules.add(new Entry(name, list)));
                }
            } else if (field.equals("defaultMode")) {
                defaultMode = string(p);
            } else if (field.equals("additionalDirectories")) {
                directories = strings(p);
            } else {
                p.skipChildren();
            }
        }
        List<Attribute> defaults = new ArrayList<>();
        plain(defaults, "defaultMode", defaultMode);
        plain(defaults, "additionalDirectories", Optional.of(String.join(", ", directories)));
        if (!defaults.isEmpty()) {
            rules.add(new Entry(DEFAULTS_NAME, defaults));
        }
        return rules;
    }

    /** 값이 있고 펴서 비지 않으면 일반 속성으로 더한다. */
    private static void plain(List<Attribute> attributes, String key, Optional<String> value) {
        value.flatMap(text -> Frontmatter.clean(text, AttributeValue.Plain.MAX_CODE_POINTS))
                .ifPresent(text -> attributes.add(ClaudeItems.plain(key, text)));
    }
}
