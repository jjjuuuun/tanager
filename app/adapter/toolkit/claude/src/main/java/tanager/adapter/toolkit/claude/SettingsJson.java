package tanager.adapter.toolkit.claude;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import tanager.domain.shared.Result;
import tanager.domain.toolkit.Attribute;
import tanager.domain.toolkit.AttributeValue;
import tanager.domain.toolkit.ToolkitItem;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.databind.json.JsonMapper;

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
 *
 * <p>형식 오류는 줄 번호만 돌려준다. Jackson 예외 메시지에는 원문 조각(비밀값일 수 있다)이 들어 있어 어디에도 쓰지 않는다.
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

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private SettingsJson() {}

    /**
     * 스트림을 읽는다(닫는 것은 부르는 쪽).
     *
     * @throws IOException 읽기 실패(형식 오류가 아니다)
     */
    static Result<Settings, FormatError> read(InputStream in) throws IOException {
        try (JsonParser p = JSON.createParser(in)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                return Result.err(new FormatError(lineOf(p.currentTokenLocation())));
            }
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
            return Result.ok(new Settings(hooks, permissions));
        } catch (JacksonIOException e) {
            throw e.getCause();
        } catch (JacksonException e) {
            return Result.err(new FormatError(lineOf(e.getLocation())));
        }
    }

    private static List<Entry> hooks(JsonParser p) {
        if (p.currentToken() != JsonToken.START_OBJECT) {
            p.skipChildren();
            return List.of();
        }
        List<Entry> entries = new ArrayList<>();
        for (String event = p.nextName(); event != null; event = p.nextName()) {
            if (p.nextToken() != JsonToken.START_ARRAY) {
                p.skipChildren();
                continue;
            }
            for (JsonToken token = p.nextToken();
                    token != null && token != JsonToken.END_ARRAY;
                    token = p.nextToken()) {
                if (token == JsonToken.START_OBJECT) {
                    entries.addAll(group(event, p));
                } else {
                    p.skipChildren();
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
                case "matcher" -> matcher = text(p).filter(m -> !m.isBlank());
                case "hooks" -> handlers.addAll(handlers(p));
                default -> p.skipChildren();
            }
        }
        Optional<String> name = Frontmatter.clean(event, ToolkitItem.MAX_NAME_CODE_POINTS);
        if (name.isEmpty()) {
            return List.of();
        }
        String full = matcher.map(m -> name.get() + SEPARATOR + m).orElse(name.get());
        List<Entry> entries = new ArrayList<>();
        for (List<Attribute> attributes : handlers) {
            entries.add(new Entry(full, attributes));
        }
        return entries;
    }

    private static List<List<Attribute>> handlers(JsonParser p) {
        if (p.currentToken() != JsonToken.START_ARRAY) {
            p.skipChildren();
            return List.of();
        }
        List<List<Attribute>> handlers = new ArrayList<>();
        for (JsonToken token = p.nextToken(); token != null && token != JsonToken.END_ARRAY; token = p.nextToken()) {
            if (token == JsonToken.START_OBJECT) {
                List<Attribute> attributes = handler(p);
                if (!attributes.isEmpty()) {
                    handlers.add(attributes);
                }
            } else {
                p.skipChildren();
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
                case "type" -> type = text(p);
                case "command" -> command = text(p);
                case "args" -> args = texts(p);
                case "url" -> url = text(p);
                case "headers" -> headerKeys = keys(p);
                case "server" -> server = text(p);
                case "tool" -> tool = text(p);
                case "timeout" -> timeout = number(p);
                default -> p.skipChildren();
            }
        }
        List<String> argv = args;
        List<Attribute> attributes = new ArrayList<>();
        type.flatMap(SettingsJson::clean).ifPresent(t -> attributes.add(ClaudeItems.plain("type", t)));
        command.map(c -> SecretMask.commandLine(c, argv))
                .flatMap(line -> Frontmatter.clean(line, MAX_COMMAND_CODE_POINTS))
                .ifPresent(line -> attributes.add(ClaudeItems.plain("command", line)));
        url.ifPresent(raw -> attributes.add(SecretMask.origin(raw)
                .map(origin -> ClaudeItems.plain("url", origin))
                .orElseGet(() -> ClaudeItems.masked("url"))));
        attributes.addAll(SecretMask.keysOnly("headers", headerKeys));
        server.flatMap(SettingsJson::clean).ifPresent(s -> attributes.add(ClaudeItems.plain("server", s)));
        tool.flatMap(SettingsJson::clean).ifPresent(t -> attributes.add(ClaudeItems.plain("tool", t)));
        timeout.ifPresent(t -> attributes.add(ClaudeItems.plain("timeout", t)));
        return unique(attributes);
    }

    private static List<Entry> permissions(JsonParser p) {
        if (p.currentToken() != JsonToken.START_OBJECT) {
            p.skipChildren();
            return List.of();
        }
        List<Entry> rules = new ArrayList<>();
        Optional<String> defaultMode = Optional.empty();
        List<String> directories = List.of();
        for (String field = p.nextName(); field != null; field = p.nextName()) {
            p.nextToken();
            if (RULE_LISTS.contains(field)) {
                List<Attribute> list = List.of(ClaudeItems.plain("list", field));
                for (String rule : texts(p)) {
                    Frontmatter.clean(SecretMask.redact(rule), ToolkitItem.MAX_NAME_CODE_POINTS)
                            .ifPresent(name -> rules.add(new Entry(name, list)));
                }
            } else if (field.equals("defaultMode")) {
                defaultMode = text(p).flatMap(SettingsJson::clean);
            } else if (field.equals("additionalDirectories")) {
                directories = texts(p);
            } else {
                p.skipChildren();
            }
        }
        List<Attribute> defaults = new ArrayList<>();
        defaultMode.ifPresent(mode -> defaults.add(ClaudeItems.plain("defaultMode", mode)));
        clean(String.join(", ", directories))
                .ifPresent(dirs -> defaults.add(ClaudeItems.plain("additionalDirectories", dirs)));
        if (!defaults.isEmpty()) {
            rules.add(new Entry(DEFAULTS_NAME, defaults));
        }
        return rules;
    }

    /** 문자열 값만. 다른 값은 건너뛴다. */
    private static Optional<String> text(JsonParser p) {
        if (p.currentToken() == JsonToken.VALUE_STRING) {
            return Optional.of(p.getValueAsString());
        }
        p.skipChildren();
        return Optional.empty();
    }

    /** 배열의 문자열 값만. 배열이 아니면 빈 목록. */
    private static List<String> texts(JsonParser p) {
        if (p.currentToken() != JsonToken.START_ARRAY) {
            p.skipChildren();
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonToken token = p.nextToken(); token != null && token != JsonToken.END_ARRAY; token = p.nextToken()) {
            text(p).ifPresent(values::add);
        }
        return values;
    }

    private static Optional<String> number(JsonParser p) {
        if (p.currentToken().isNumeric()) {
            return Optional.of(p.getValueAsString());
        }
        p.skipChildren();
        return Optional.empty();
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

    private static Optional<String> clean(String text) {
        return Frontmatter.clean(text, AttributeValue.Plain.MAX_CODE_POINTS);
    }

    // 펴서 같아진 headers 키는 앞의 것만 둔다 — 도메인 불변식: key 유일
    private static List<Attribute> unique(List<Attribute> attributes) {
        Set<String> seen = new LinkedHashSet<>();
        List<Attribute> out = new ArrayList<>();
        for (Attribute attribute : attributes) {
            if (seen.add(attribute.key())) {
                out.add(attribute);
            }
        }
        return out;
    }

    private static OptionalInt lineOf(TokenStreamLocation location) {
        if (location == null || location.getLineNr() < 1) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(location.getLineNr());
    }
}
