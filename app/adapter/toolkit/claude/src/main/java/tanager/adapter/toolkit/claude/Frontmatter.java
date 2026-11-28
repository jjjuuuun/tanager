package tanager.adapter.toolkit.claude;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import tanager.domain.shared.Result;
import tools.jackson.core.JacksonException;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Markdown 파일 맨 앞의 YAML 프런트매터({@code ---} 와 {@code ---} 사이). 글자로 쓸 수 있는 값(글자 · 숫자 · 참거짓, 그 목록)만 든다.
 * 앞부분 {@link #MAX_BYTES} 바이트 넘게 읽지 않는다.
 */
record Frontmatter(Map<String, String> values) {

    /** 한 파일에서 읽는 최대 바이트. 닫는 {@code ---} 가 이 안에 없으면 형식 오류다. */
    static final int MAX_BYTES = 64 * 1024;

    private static final String FENCE = "---";
    private static final char BOM = '﻿';
    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    Frontmatter {
        values = Map.copyOf(values);
    }

    /** 스트림을 읽고 닫는다. 첫 줄이 {@code ---} 가 아니면 빈 값. */
    static Result<Frontmatter, FormatError> read(InputStream raw) throws IOException {
        List<String> lines = new ArrayList<>();
        boolean closed = false;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new BoundedInputStream(raw, MAX_BYTES), StandardCharsets.UTF_8))) {
            String first = reader.readLine();
            if (first == null || !isFence(stripBom(first))) {
                return Result.ok(new Frontmatter(Map.of()));
            }
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (isFence(line)) {
                    closed = true;
                    break;
                }
                lines.add(line);
            }
        }
        if (!closed) {
            return Result.err(new FormatError(OptionalInt.of(1)));
        }
        return parse(String.join("\n", lines));
    }

    /**
     * 그 키의 값을 한 줄 글자로. 제어 문자 · 줄바꿈 · 연속 공백은 공백 하나로 펴고, {@code max} 코드 포인트를 넘으면 끝을 {@code …} 로 자른다. 없거나
     * 비면 empty.
     */
    Optional<String> text(String key, int max) {
        return Optional.ofNullable(values.get(key)).flatMap(value -> clean(value, max));
    }

    /** {@link #text} 와 같은 규칙으로 아무 글자나 한 줄로 편다. */
    static Optional<String> clean(String value, int max) {
        StringBuilder flat = new StringBuilder();
        boolean space = false;
        for (int cp : value.codePoints().toArray()) {
            if (Character.isISOControl(cp) || Character.isWhitespace(cp)) {
                space = true;
                continue;
            }
            if (space && !flat.isEmpty()) {
                flat.append(' ');
            }
            space = false;
            flat.appendCodePoint(cp);
        }
        String text = flat.toString();
        int length = text.codePointCount(0, text.length());
        if (length > max) {
            text = text.substring(0, text.offsetByCodePoints(0, max - 1)) + "…";
        }
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }

    private static Result<Frontmatter, FormatError> parse(String yaml) {
        if (yaml.isBlank()) {
            return Result.ok(new Frontmatter(Map.of()));
        }
        JsonNode root;
        try {
            root = YAML.readTree(yaml);
        } catch (JacksonException e) {
            return Result.err(new FormatError(lineOf(e.getLocation())));
        }
        if (root == null || root.isMissingNode() || root.isNull()) {
            return Result.ok(new Frontmatter(Map.of()));
        }
        if (!root.isObject()) {
            return Result.err(new FormatError(OptionalInt.empty()));
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> field : root.properties()) {
            textOf(field.getValue()).ifPresent(text -> values.put(field.getKey(), text));
        }
        return Result.ok(new Frontmatter(values));
    }

    // 여는 --- 가 1번 줄이므로 YAML 의 줄 번호(1부터)에 1을 더한다
    private static OptionalInt lineOf(TokenStreamLocation location) {
        if (location == null || location.getLineNr() < 1) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(location.getLineNr() + 1);
    }

    private static Optional<String> textOf(JsonNode node) {
        if (node.isValueNode() && !node.isNull()) {
            return Optional.of(node.asString());
        }
        if (!node.isArray()) {
            return Optional.empty();
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode element : node) {
            if (element.isValueNode() && !element.isNull()) {
                parts.add(element.asString());
            }
        }
        return parts.isEmpty() ? Optional.empty() : Optional.of(String.join(", ", parts));
    }

    private static boolean isFence(String line) {
        return line.stripTrailing().equals(FENCE);
    }

    private static String stripBom(String line) {
        return !line.isEmpty() && line.charAt(0) == BOM ? line.substring(1) : line;
    }
}
