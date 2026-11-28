package tanager.adapter.toolkit.claude;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 실제 {@code ~/.claude.json} 처럼 큰 값(대화 · 캐시)이 앞에 몰린 합성 파일. {@code /home/dev/app} 의 서버 {@code db} 와 맨 위
 * mcpServers 의 {@code tail} 은 맨 끝에 둔다 — 끝까지 걸어야 찾는다.
 */
final class LargeClaudeJson {

    /** 시험 크기(이슈 #212 의 인수 조건). */
    static final long SIZE = 50L * 1024 * 1024;

    private LargeClaudeJson() {}

    static void write(Path file, long targetBytes) throws IOException {
        Files.createDirectories(file.getParent());
        String blob = "x".repeat(32 * 1024);
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write("{\"cachedBlob\": \"" + "y".repeat(4 * 1024 * 1024) + "\",\n\"projects\": {\n");
            long written = 4L * 1024 * 1024;
            for (int i = 0; written < targetBytes; i++) {
                out.write("\"/home/dev/p" + i + "\": {\"history\": [{\"display\": \"" + blob
                        + "\"}], \"mcpServers\": {\"s" + i + "\": {\"command\": \"c\"}}},\n");
                written += blob.length() + 100;
            }
            out.write("\"/home/dev/app\": {\"mcpServers\": {\"db\": {\"command\": \"local-db\"}}}\n},\n");
            out.write("\"mcpServers\": {\"tail\": {\"command\": \"t\"}}}\n");
        }
    }
}
