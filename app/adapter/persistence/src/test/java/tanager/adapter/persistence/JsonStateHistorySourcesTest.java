package tanager.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tanager.application.port.out.HistoryStoreError;
import tanager.application.port.out.LoadedHistory;
import tanager.domain.history.HistoryPolicy;
import tanager.domain.history.ImportBatch;
import tanager.domain.history.ImportOrigin;
import tanager.domain.history.ImportedVisit;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.VisitSource;
import tanager.domain.history.VisitedDirectory;
import tanager.domain.history.WorkHistory;
import tanager.domain.history.WorkspacePath;
import tanager.domain.shared.Result;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** state.json 의 항목별 출처(sources)와 가져오기 워터마크(history.importedUntil)를 읽고 쓴다(#207, SDD §9.2). */
class JsonStateHistorySourcesTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-11-26T01:02:03Z");
    private static final ShellEnvironment WINDOWS = new ShellEnvironment.Windows();
    private static final ShellEnvironment UBUNTU = new ShellEnvironment.Wsl("Ubuntu-24.04");
    private static final ImportOrigin CLAUDE_UBUNTU = new ImportOrigin(VisitSource.CLAUDE_CODE, UBUNTU);
    private static final VisitSource FUTURE = new VisitSource("future-tool");

    @TempDir
    Path dir;

    private final List<JsonStateRepository> opened = new ArrayList<>();

    @AfterEach
    void closeAll() {
        opened.forEach(JsonStateRepository::close);
    }

    private Path file() {
        return dir.resolve("state.json");
    }

    private JsonStateRepository repository() {
        JsonStateRepository repository =
                new JsonStateRepository(file(), HistoryPolicy.DEFAULT, InstantSource.fixed(NOW));
        opened.add(repository);
        return repository;
    }

    private static String fixture(String name) {
        try (InputStream in = JsonStateHistorySourcesTest.class.getResourceAsStream("/state/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void write(String text) throws IOException {
        Files.writeString(file(), text, StandardCharsets.UTF_8);
    }

    private JsonNode saved() throws IOException {
        return JSON.readTree(Files.readAllBytes(file()));
    }

    private static WorkspacePath path(ShellEnvironment env, String raw) {
        return WorkspacePath.of(env, raw).value().orElseThrow();
    }

    private static LoadedHistory loaded(Result<LoadedHistory, HistoryStoreError> result) {
        return result.value().orElseThrow(() -> new AssertionError("load 가 실패했다: " + result.error()));
    }

    private static String entry(String sourcesJson) {
        return "{ \"env\": { \"kind\": \"windows\" }, \"path\": \"C:\\\\work\","
                + " \"firstVisited\": \"2026-11-21T01:00:00Z\", \"lastVisited\": \"2026-11-23T08:30:00Z\","
                + " \"visitCount\": 3, \"pinned\": false, \"alias\": null, \"hidden\": false"
                + sourcesJson + " }";
    }

    private static String v2With(String entry, String historyExtra) {
        return "{ \"schemaVersion\": 2, \"history\": { \"entries\": [" + entry + "]" + historyExtra + " } }";
    }

    private static VisitedDirectory only(LoadedHistory result) {
        assertThat(result.history().entries()).hasSize(1);
        return result.history().entries().values().iterator().next();
    }

    @Test
    @DisplayName("모르는 출처는 담고 형식이 틀린 원소만 버려 id 순으로 저장한다")
    void unknownSourcesKeptMalformedDropped() throws IOException {
        // given
        write(v2With(entry(", \"sources\": [\"shell\", \"future-tool\", \"Bad Value\"]"), ""));
        JsonStateRepository repository = repository();

        // when
        LoadedHistory result = loaded(repository.load());
        repository.save(result.history());

        // then
        assertThat(only(result).sources()).containsExactly(FUTURE, VisitSource.SHELL);
        assertThat(saved().get("history").get("entries").get(0).get("sources"))
                .isEqualTo(JSON.readTree("[\"future-tool\",\"shell\"]"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"", ", \"sources\": null", ", \"sources\": []", ", \"sources\": [\"Bad\", 3]"})
    @DisplayName("sources 가 없거나 쓸 원소가 없으면 shell 로 읽는다")
    void missingOrEmptySourcesReadAsShell(String sourcesJson) throws IOException {
        // given
        write(v2With(entry(sourcesJson), ""));

        // when
        LoadedHistory result = loaded(repository().load());

        // then
        assertThat(only(result).sources()).containsExactly(VisitSource.SHELL);
    }

    @Test
    @DisplayName("v1 · v2 픽스처의 sources 가 도메인 출처로 읽힌다")
    void fixturesReadSources() throws IOException {
        // given
        write(fixture("v1-history-only.json"));

        // when
        LoadedHistory v1 = loaded(repository().load());

        // then
        WorkspacePath project = path(UBUNTU, "/home/me/한글 프로젝트");
        assertThat(v1.history().find(project).orElseThrow().sources())
                .containsExactly(VisitSource.CLAUDE_CODE, VisitSource.SHELL);
        assertThat(v1.history()
                        .find(path(WINDOWS, "C:\\work\\infra"))
                        .orElseThrow()
                        .sources())
                .containsExactly(VisitSource.SHELL);
        assertThat(v1.history().importedUntil()).isEmpty();
        opened.forEach(JsonStateRepository::close);

        write(fixture("v2.json"));
        LoadedHistory v2 = loaded(repository().load());
        assertThat(v2.history().find(project).orElseThrow().usedBy(VisitSource.CLAUDE_CODE))
                .isTrue();
        assertThat(v2.history().importedUntil()).isEmpty();
    }

    @Test
    @DisplayName("importedUntil 이 없는 v2 파일은 빈 워터마크로 읽힌다")
    void missingWatermarkIsEmpty() throws IOException {
        // given
        write(v2With(entry(""), ""));

        // when
        LoadedHistory result = loaded(repository().load());

        // then
        assertThat(result.history().importedUntil()).isEmpty();
    }

    @Test
    @DisplayName("가져온 이력을 저장하면 importedUntil 을 SDD 9.2 모양으로 쓰고 다시 읽으면 같다")
    void watermarkRoundTrips() throws IOException {
        // given
        JsonStateRepository repository = repository();
        repository.load();
        WorkspacePath a = path(UBUNTU, "/home/me/a");
        WorkHistory history = WorkHistory.empty(HistoryPolicy.DEFAULT)
                .recordVisit(path(WINDOWS, "C:\\w"), NOW)
                .importVisits(new ImportBatch(
                        VisitSource.CLAUDE_CODE,
                        UBUNTU,
                        Instant.parse("2026-11-25T00:00:00Z"),
                        List.of(new ImportedVisit(
                                a, Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-11-02T00:00:00Z")))))
                .importVisits(new ImportBatch(FUTURE, WINDOWS, Instant.parse("2026-11-24T00:00:00Z"), List.of()));

        // when
        repository.save(history);
        JsonNode root = saved();
        repository.close();
        LoadedHistory reloaded = loaded(repository().load());

        // then
        assertThat(root.get("history").get("importedUntil"))
                .isEqualTo(JSON.readTree("["
                        + "{\"source\":\"claude-code\",\"env\":{\"kind\":\"wsl\",\"distro\":\"Ubuntu-24.04\"},"
                        + "\"until\":\"2026-11-25T00:00:00Z\"},"
                        + "{\"source\":\"future-tool\",\"env\":{\"kind\":\"windows\"},"
                        + "\"until\":\"2026-11-24T00:00:00Z\"}]"));
        assertThat(entryAt(root, "/home/me/a").get("sources")).isEqualTo(JSON.readTree("[\"claude-code\"]"));
        assertThat(reloaded.history()).isEqualTo(history);
    }

    @Test
    @DisplayName("importedUntil 의 틀린 원소는 버리고 같은 (출처, 환경)이 여럿이면 가장 늦은 시각을 쓴다")
    void malformedWatermarksSkipped() throws IOException {
        // given
        String ubuntu = "{\"kind\":\"wsl\",\"distro\":\"Ubuntu-24.04\"}";
        write(v2With(
                entry(""),
                ", \"importedUntil\": ["
                        + "{\"source\":\"claude-code\",\"env\":" + ubuntu + ",\"until\":\"2026-11-20T00:00:00Z\"},"
                        + "{\"source\":\"claude-code\",\"env\":" + ubuntu + ",\"until\":\"2026-11-22T00:00:00Z\"},"
                        + "{\"source\":\"Bad Value\",\"env\":" + ubuntu + ",\"until\":\"2026-11-22T00:00:00Z\"},"
                        + "{\"source\":\"claude-code\",\"env\":{\"kind\":\"mars\"},\"until\":\"2026-11-22T00:00:00Z\"},"
                        + "{\"source\":\"claude-code\",\"env\":{\"kind\":\"windows\"},\"until\":\"어제\"},"
                        + "{\"source\":\"claude-code\",\"env\":{\"kind\":\"windows\"}},"
                        + "\"문자열\", 3, null]"));

        // when
        LoadedHistory result = loaded(repository().load());

        // then
        assertThat(result.history().importedUntil())
                .isEqualTo(Map.of(CLAUDE_UBUNTU, Instant.parse("2026-11-22T00:00:00Z")));
        assertThat(result.history().entries()).hasSize(1);
    }

    @Test
    @DisplayName("importedUntil 이 배열이 아니면 빈 워터마크로 읽고 항목은 그대로 읽는다")
    void nonArrayWatermarkIgnored() throws IOException {
        // given
        write(v2With(entry(""), ", \"importedUntil\": {\"x\": 1}"));

        // when
        LoadedHistory result = loaded(repository().load());

        // then
        assertThat(result.history().importedUntil()).isEmpty();
        assertThat(result.history().entries()).hasSize(1);
    }

    @Test
    @DisplayName("워터마크가 없는 이력을 저장하면 importedUntil 을 쓰지 않는다 — 가져온 적 없는 파일은 그대로")
    void emptyWatermarkNotWritten() throws IOException {
        // given
        JsonStateRepository repository = repository();
        repository.load();

        // when
        repository.save(WorkHistory.empty(HistoryPolicy.DEFAULT).recordVisit(path(WINDOWS, "C:\\w"), NOW));

        // then
        assertThat(saved().get("history").has("importedUntil")).isFalse();
        assertThat(saved().get("schemaVersion").intValue()).isEqualTo(2);
    }

    private static JsonNode entryAt(JsonNode root, String rawPath) {
        for (JsonNode e : root.get("history").get("entries")) {
            if (e.get("path").stringValue().equals(rawPath)) {
                return e;
            }
        }
        throw new AssertionError("항목이 없다: " + rawPath);
    }
}
