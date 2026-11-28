package tanager.adapter.toolkit.claude;

import static org.assertj.core.api.Assertions.assertThat;
import static tanager.adapter.toolkit.claude.ClaudeFixture.path;
import static tanager.adapter.toolkit.claude.Scans.ok;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tanager.application.port.out.ToolkitQuery;
import tanager.application.port.out.ToolkitScan;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.toolkit.Attribute;
import tanager.domain.toolkit.AttributeValue;
import tanager.domain.toolkit.ToolkitItem;
import tanager.domain.toolkit.ToolkitWarning;

/**
 * 비밀값이 어댑터 밖으로 나가지 않는다(SDD §12, FR-TK-07 · NFR-06, #212). MCP 픽스처의 모든 비밀 표식({@code S3CR3T-FAKE-<n>}, 합성)과
 * {@code ~/.claude.json} 의 계정 정보가 scan 결과의 어떤 글자(항목 · 속성 · 경고 · 지문 · {@code toString})에도, 그동안 남긴 로그에도 없다.
 */
class SecretMaskingTest {

    private static final ShellEnvironment WSL = new ShellEnvironment.Wsl("Ubuntu");
    private static final WorkspacePath PROJECT = path(WSL, "/home/dev/app");
    private static final WorkspacePath BROKEN = path(WSL, "/home/dev/broken");
    private static final WorkspacePath HOME = path(WSL, "/home/dev");
    private static final WorkspacePath MANAGED = path(WSL, "/etc/claude-code");

    /** 픽스처에 심은 비밀 표식 수(1 … N). */
    private static final int SECRETS = 14;

    private static final Pattern SECRET = Pattern.compile("S3CR3T-FAKE-\\d+");
    private static final List<String> ACCOUNT = List.of("fake.person@example.invalid", "00000000abcd");

    @TempDir
    Path tmp;

    @Test
    @DisplayName("픽스처의 모든 S3CR3T 표식과 계정 정보가 scan 결과 · toString · 로그 어디에도 없다")
    void noSecretLeaves() throws IOException {
        // given
        ClaudeFixture fixture = new ClaudeFixture()
                .copy("mcp/home", tmp.resolve("home"), HOME)
                .copy("mcp/project", tmp.resolve("project"), PROJECT)
                .copy("mcp/broken", tmp.resolve("broken"), BROKEN)
                .copy("mcp/managed", tmp.resolve("managed"), MANAGED);
        assertThat(plantedSecrets(tmp)).as("픽스처가 비밀 표식을 모두 담고 있어야 시험이 뜻이 있다").hasSize(SECRETS);
        ClaudeToolkitProvider provider = new ClaudeToolkitProvider();

        // when
        List<String> outputs = new ArrayList<>();
        List<LogRecord> logs = captureLogs(() -> {
            for (WorkspacePath project : List.of(PROJECT, BROKEN)) {
                ToolkitQuery query = new ToolkitQuery(project, Optional.of(HOME), fixture.hostFunction());
                ToolkitScan scan = ok(provider.scan(query));
                outputs.addAll(texts(scan));
                outputs.add(provider.stamp(query).orElseThrow().toString());
            }
        });

        // then
        assertThat(outputs).anyMatch(text -> text.contains("Masked"));
        assertThat(outputs).anyMatch(text -> text.contains("Malformed"));
        List<String> logTexts = logs.stream().map(SecretMaskingTest::text).toList();
        for (String text : Stream.concat(outputs.stream(), logTexts.stream()).toList()) {
            assertThat(SECRET.matcher(text).find()).as("비밀 표식이 새어 나감: %s", text).isFalse();
            for (String account : ACCOUNT) {
                assertThat(text).doesNotContain(account);
            }
        }
    }

    /** 결과에 든 모든 글자 — 항목 · 속성 · 경고의 {@code toString} 과 각 문자열 필드. */
    private static List<String> texts(ToolkitScan scan) {
        List<String> texts = new ArrayList<>();
        texts.add(scan.toString());
        for (ToolkitItem item : scan.items()) {
            texts.add(item.toString());
            texts.add(item.name());
            item.description().ifPresent(texts::add);
            for (Attribute attribute : item.attributes()) {
                texts.add(attribute.key());
                if (attribute.value() instanceof AttributeValue.Plain(String value)) {
                    texts.add(value);
                }
            }
        }
        for (ToolkitWarning warning : scan.warnings()) {
            texts.add(warning.toString());
        }
        return texts;
    }

    private static List<String> plantedSecrets(Path root) throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Matcher matcher = SECRET.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    if (!found.contains(matcher.group())) {
                        found.add(matcher.group());
                    }
                }
            }
        }
        return found;
    }

    /** {@code tanager} 아래 모든 로거의 기록(모든 수준)을 모은다. */
    private static List<LogRecord> captureLogs(Runnable action) {
        List<LogRecord> records = new ArrayList<>();
        Logger logger = Logger.getLogger("tanager");
        Level previous = logger.getLevel();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        try {
            action.run();
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previous);
        }
        return records;
    }

    private static String text(LogRecord record) {
        StringBuilder out = new StringBuilder(Objects.requireNonNullElse(record.getMessage(), ""));
        out.append(Arrays.toString(Objects.requireNonNullElse(record.getParameters(), new Object[0])));
        if (record.getThrown() != null) {
            StringWriter trace = new StringWriter();
            record.getThrown().printStackTrace(new PrintWriter(trace));
            out.append(trace);
        }
        return out.toString();
    }
}
