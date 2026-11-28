package tanager.adapter.toolkit.claude;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 테스트용 가짜 홈. {@code <home>/.claude/projects/<폴더>/<파일>} 에 합성 대화 기록을 쓴다. 실제 기록의 내용은 쓰지 않는다 — 줄의 종류와 키 모양만
 * 실제 형식을 따른다(ADR-0020).
 */
final class ClaudeHome {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Path home;

    ClaudeHome(Path home) {
        this.home = home;
    }

    Path home() {
        return home;
    }

    Path projects() {
        return home.resolve(".claude").resolve("projects");
    }

    Path conversation(String folder, String fileName, Instant modified, String... lines) {
        return write(folder, fileName, modified, String.join("\n", lines) + "\n");
    }

    Path resource(String folder, String fileName, Instant modified, String resource) {
        try (InputStream in = ClaudeHome.class.getResourceAsStream("/transcripts/" + resource)) {
            if (in == null) {
                throw new IllegalStateException("픽스처가 없다: " + resource);
            }
            return write(folder, fileName, modified, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path write(String folder, String fileName, Instant modified, String content) {
        try {
            Path dir = Files.createDirectories(projects().resolve(folder));
            Path file = dir.resolve(fileName);
            Files.writeString(file, content, StandardCharsets.UTF_8);
            Files.setLastModifiedTime(file, FileTime.from(modified));
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- 줄 만들기(실제 형식의 키 모양만 따른 합성 값) ----

    static String user(String cwd, String text) {
        ObjectNode line = base("user", cwd);
        line.putObject("message").put("role", "user").put("content", text);
        return line.toString();
    }

    static String userBlocks(String cwd, List<String> texts) {
        ObjectNode line = base("user", cwd);
        ArrayNode content = line.putObject("message").put("role", "user").putArray("content");
        for (String text : texts) {
            content.addObject().put("type", "text").put("text", text);
        }
        content.addObject().put("type", "image").putObject("source").put("type", "base64");
        return line.toString();
    }

    static String meta(String cwd, String text) {
        ObjectNode line = base("user", cwd);
        line.put("isMeta", true);
        line.putObject("message").put("role", "user").put("content", text);
        return line.toString();
    }

    static String toolResult(String cwd, String output) {
        ObjectNode line = base("user", cwd);
        ArrayNode content = line.putObject("message").put("role", "user").putArray("content");
        content.addObject().put("type", "tool_result").put("tool_use_id", "t-1").put("content", output);
        return line.toString();
    }

    static String assistant(String cwd) {
        ObjectNode line = base("assistant", cwd);
        line.putObject("message")
                .put("role", "assistant")
                .putArray("content")
                .addObject()
                .put("type", "text")
                .put("text", "synthetic answer");
        return line.toString();
    }

    /** cwd 가 없는 앞부분 줄(옛 형식의 summary). */
    static String summary() {
        ObjectNode line = JSON.createObjectNode();
        line.put("type", "summary").put("summary", "synthetic summary").put("leafUuid", "l-1");
        return line.toString();
    }

    /** cwd 가 없는 앞부분 줄. */
    static String snapshot() {
        ObjectNode line = JSON.createObjectNode();
        line.put("type", "file-history-snapshot").put("messageId", "m-0").put("isSnapshotUpdate", false);
        line.putObject("snapshot").putObject("trackedFileBackups");
        return line.toString();
    }

    private static ObjectNode base(String type, String cwd) {
        ObjectNode line = JSON.createObjectNode();
        line.putNull("parentUuid");
        line.put("isSidechain", false)
                .put("userType", "external")
                .put("cwd", cwd)
                .put("sessionId", "00000000-0000-4000-8000-000000000001")
                .put("version", "2.0.0")
                .put("type", type)
                .put("uuid", "u-" + type)
                .put("timestamp", "2026-11-20T09:00:00.000Z");
        return line;
    }
}
