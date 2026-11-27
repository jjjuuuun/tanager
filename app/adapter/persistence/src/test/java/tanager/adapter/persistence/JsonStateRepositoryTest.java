package tanager.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tanager.application.port.out.HistoryLoadNotice;
import tanager.application.port.out.HistoryStoreError;
import tanager.application.port.out.LoadedHistory;
import tanager.application.port.out.StoreAccess;
import tanager.domain.history.Alias;
import tanager.domain.history.HistoryPolicy;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.VisitedDirectory;
import tanager.domain.history.WorkHistory;
import tanager.domain.history.WorkspacePath;
import tanager.domain.shared.Result;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** state.json 의 history 부분(ADR-0013, SDD §9.2)을 읽고 쓰는 {@link JsonStateRepository} 의 파일 시스템 동작. */
class JsonStateRepositoryTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-11-26T01:02:03Z");
    private static final ShellEnvironment WINDOWS = new ShellEnvironment.Windows();
    private static final ShellEnvironment UBUNTU = new ShellEnvironment.Wsl("Ubuntu-24.04");

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
        return repositoryAt(file());
    }

    private JsonStateRepository repositoryAt(Path file) {
        JsonStateRepository repository = new JsonStateRepository(file, HistoryPolicy.DEFAULT, InstantSource.fixed(NOW));
        opened.add(repository);
        return repository;
    }

    // 손상 원본을 옆에 두는 일(.bak 이 없으면 옮기기, 있으면 복사)을 모두 같은 동작으로 바꾼다 — 실패 주입용
    private JsonStateRepository repositoryMovingWith(JsonStateRepository.Mover mover) {
        JsonStateRepository repository =
                new JsonStateRepository(file(), HistoryPolicy.DEFAULT, InstantSource.fixed(NOW), mover, mover::move);
        opened.add(repository);
        return repository;
    }

    private JsonStateRepository repositoryCopyingWith(JsonStateRepository.Copier copier) {
        JsonStateRepository repository = new JsonStateRepository(
                file(), HistoryPolicy.DEFAULT, InstantSource.fixed(NOW), (from, to) -> Files.move(from, to), copier);
        opened.add(repository);
        return repository;
    }

    private List<String> corruptFiles() throws IOException {
        try (Stream<Path> list = Files.list(dir)) {
            return list.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("state.json.corrupt-"))
                    .toList();
        }
    }

    private static String fixture(String name) {
        try (InputStream in = JsonStateRepositoryTest.class.getResourceAsStream("/state/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void write(Path target, String text) throws IOException {
        Files.writeString(target, text, StandardCharsets.UTF_8);
    }

    private static WorkspacePath path(ShellEnvironment env, String raw) {
        return WorkspacePath.of(env, raw).value().orElseThrow();
    }

    private static LoadedHistory loaded(Result<LoadedHistory, HistoryStoreError> result) {
        return result.value().orElseThrow(() -> new AssertionError("load 가 실패했다: " + result.error()));
    }

    private static WorkHistory historyOf(String raw) {
        return WorkHistory.restore(
                List.of(new VisitedDirectory(path(WINDOWS, raw), NOW, NOW, 2, false, Optional.empty(), false)),
                HistoryPolicy.DEFAULT);
    }

    private static String entryJson(String env, String path, int count) {
        return "{ \"env\": " + env + ", \"path\": " + JSON.writeValueAsString(path)
                + ", \"firstVisited\": \"2026-11-21T01:00:00Z\", \"lastVisited\": \"2026-11-23T08:30:00Z\","
                + " \"visitCount\": " + count + ", \"pinned\": false, \"alias\": null, \"hidden\": false,"
                + " \"sources\": [\"shell\"] }";
    }

    private static String entryWithSources(String path, Optional<String> sources) {
        return "{ \"env\": { \"kind\": \"windows\" }, \"path\": " + JSON.writeValueAsString(path)
                + ", \"firstVisited\": \"2026-11-21T01:00:00Z\", \"lastVisited\": \"2026-11-23T08:30:00Z\","
                + " \"visitCount\": 3, \"pinned\": false, \"alias\": null, \"hidden\": false"
                + sources.map(json -> ", \"sources\": " + json).orElse("") + " }";
    }

    private static String stateWith(String... entries) {
        return "{ \"schemaVersion\": 1, \"history\": { \"entries\": [" + String.join(",", entries) + "] } }";
    }

    // ---- 읽기 ----

    @Test
    @DisplayName("파일이 없으면 빈 이력이고 쓰기 가능하며 알림이 없고 파일을 만들지 않는다")
    void missingFileLoadsEmptyWritableWithoutCreatingIt() {
        // when
        LoadedHistory result = loaded(repository().load());

        // then
        assertThat(result.history()).isEqualTo(WorkHistory.empty(HistoryPolicy.DEFAULT));
        assertThat(result.access()).isEqualTo(StoreAccess.WRITABLE);
        assertThat(result.notices()).isEmpty();
        assertThat(file()).doesNotExist();
    }

    @Test
    @DisplayName("SDD 9.2 예시를 읽으면 항목 두 개의 값이 그대로 나온다")
    void loadsSddExample() throws IOException {
        // given
        write(file(), fixture("v1-history.json"));

        // when
        LoadedHistory result = loaded(repository().load());

        // then
        assertThat(result.notices()).isEmpty();
        assertThat(result.access()).isEqualTo(StoreAccess.WRITABLE);
        assertThat(result.history().entries()).hasSize(2);
        assertThat(result.history().find(path(UBUNTU, "/mnt/c/Users/user/Desktop/Tanager")))
                .contains(new VisitedDirectory(
                        path(UBUNTU, "/mnt/c/Users/user/Desktop/Tanager"),
                        Instant.parse("2026-11-20T09:12:03Z"),
                        Instant.parse("2026-11-24T11:05:41Z"),
                        37,
                        true,
                        Optional.of(new Alias("Tanager")),
                        false));
        assertThat(result.history().find(path(WINDOWS, "C:\\work\\infra")))
                .contains(new VisitedDirectory(
                        path(WINDOWS, "C:\\work\\infra"),
                        Instant.parse("2026-11-21T01:00:00Z"),
                        Instant.parse("2026-11-23T08:30:00Z"),
                        5,
                        false,
                        Optional.empty(),
                        false));
    }

    static Stream<Arguments> unreadableEntries() {
        String win = "{ \"kind\": \"windows\" }";
        String complete = "\"env\": " + win + ", \"path\": \"C:\\\\work\\\\bad\", \"visitCount\": 3,"
                + " \"pinned\": false, \"hidden\": false, \"alias\": null";
        return Stream.of(
                Arguments.of("상대 경로", entryJson(win, "relative\\dir", 3)),
                Arguments.of("모르는 환경", entryJson("{ \"kind\": \"solaris\" }", "C:\\work\\bad", 3)),
                Arguments.of("방문 횟수 0", entryJson(win, "C:\\work\\bad", 0)),
                Arguments.of(
                        "필수 필드(lastVisited) 누락", "{" + complete + ", \"firstVisited\": \"2026-11-21T01:00:00Z\" }"),
                Arguments.of("distro 없는 wsl", entryJson("{ \"kind\": \"wsl\" }", "/home/me/proj", 3)),
                Arguments.of("객체가 아닌 항목(숫자)", "42"),
                Arguments.of("객체가 아닌 항목(문자열)", "\"C:\\\\work\\\\bad\""),
                Arguments.of(
                        "시각 형식 오류",
                        "{" + complete + ", \"firstVisited\": \"어제\", \"lastVisited\": \"2026-11-23T08:30:00Z\" }"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unreadableEntries")
    @DisplayName("해석할 수 없는 항목은 그것만 빼고 EntriesSkipped(1) 로 알린다")
    void invalidEntryIsSkippedAndReported(String caseName, String entry) throws IOException {
        // given
        write(file(), stateWith(entryJson("{ \"kind\": \"windows\" }", "C:\\work\\good", 4), entry));

        // when
        LoadedHistory result = loaded(repository().load());

        // then
        assertThat(result.history().entries()).containsOnlyKeys(path(WINDOWS, "C:\\work\\good"));
        assertThat(result.notices()).containsExactly(new HistoryLoadNotice.EntriesSkipped(1));
        assertThat(result.access()).isEqualTo(StoreAccess.WRITABLE);
    }

    @Test
    @DisplayName("Windows 에서 대소문자만 다른 두 항목은 하나로 합친다")
    void caseOnlyDuplicatesMergeOnWindows() throws IOException {
        // given
        write(
                file(),
                stateWith(
                        entryJson("{ \"kind\": \"windows\" }", "C:\\Work", 3),
                        entryJson("{ \"kind\": \"windows\" }", "c:\\work", 4)));

        // when
        LoadedHistory result = loaded(repository().load());

        // then
        assertThat(result.history().entries()).hasSize(1);
        assertThat(result.history()
                        .find(path(WINDOWS, "C:\\work"))
                        .orElseThrow()
                        .visitCount())
                .isEqualTo(7);
        assertThat(result.notices()).isEmpty();
    }

    // ---- 손상 ----

    @Test
    @DisplayName("state.json 이 손상되고 .bak 이 정상이면 .bak 내용과 RecoveredFromBackup 을 돌려준다")
    void corruptFileRecoversFromBackup() throws IOException {
        // given
        write(file(), fixture("v1-corrupt.json"));
        write(dir.resolve("state.json.bak"), fixture("v1-history.json"));

        // when
        LoadedHistory result = loaded(repository().load());

        // then
        assertThat(result.history().entries()).hasSize(2);
        assertThat(result.notices())
                .containsExactly(
                        new HistoryLoadNotice.RecoveredFromBackup(Optional.of("state.json.corrupt-20261126T010203Z")));
        assertThat(result.access()).isEqualTo(StoreAccess.WRITABLE);
    }

    @Test
    @DisplayName(".bak 으로 복구한 뒤 첫 저장은 손상된 파일로 .bak 을 덮어쓰지 않는다")
    void recoveredSaveKeepsGoodBackup() throws IOException {
        // given
        write(file(), fixture("v1-corrupt.json"));
        write(dir.resolve("state.json.bak"), fixture("v1-history.json"));
        JsonStateRepository repository = repository();
        repository.load();

        // when
        repository.save(historyOf("C:\\new"));

        // then
        assertThat(Files.readString(dir.resolve("state.json.bak"), StandardCharsets.UTF_8))
                .isEqualTo(fixture("v1-history.json"));
    }

    @Test
    @DisplayName("state.json 과 .bak 이 둘 다 손상되면 빈 이력이고 원본은 state.json.corrupt-UTC 로 옮겨진다")
    void bothCorruptMovesOriginalAside() throws IOException {
        // given
        write(file(), fixture("v1-corrupt.json"));
        write(dir.resolve("state.json.bak"), "not json at all");

        // when
        LoadedHistory result = loaded(repository().load());

        // then
        String moved = "state.json.corrupt-20261126T010203Z";
        assertThat(result.history()).isEqualTo(WorkHistory.empty(HistoryPolicy.DEFAULT));
        assertThat(result.notices()).containsExactly(new HistoryLoadNotice.CorruptMovedAside(moved));
        assertThat(result.access()).isEqualTo(StoreAccess.WRITABLE);
        assertThat(file()).doesNotExist();
        assertThat(Files.readString(dir.resolve(moved), StandardCharsets.UTF_8)).isEqualTo(fixture("v1-corrupt.json"));
    }

    @Test
    @DisplayName("손상되고 .bak 이 없을 때도 원본을 옆으로 옮기고 빈 이력으로 시작한다")
    void corruptWithoutBackupMovesOriginalAside() throws IOException {
        // given
        write(file(), fixture("v1-corrupt.json"));

        // when
        LoadedHistory result = loaded(repository().load());

        // then
        assertThat(result.history().entries()).isEmpty();
        assertThat(result.notices())
                .containsExactly(new HistoryLoadNotice.CorruptMovedAside("state.json.corrupt-20261126T010203Z"));
        assertThat(dir.resolve("state.json.corrupt-20261126T010203Z")).exists();
    }

    static Stream<Arguments> brokenSkeletons() {
        return Stream.of(
                Arguments.of("최상위가 객체가 아님", "[ { \"schemaVersion\": 1 } ]"),
                Arguments.of("schemaVersion 이 문자열", "{ \"schemaVersion\": \"1\", \"history\": { \"entries\": [] } }"),
                Arguments.of("schemaVersion 이 소수", "{ \"schemaVersion\": 1.5, \"history\": { \"entries\": [] } }"),
                Arguments.of("schemaVersion 이 0", "{ \"schemaVersion\": 0, \"history\": { \"entries\": [] } }"),
                Arguments.of("history 가 객체가 아님", "{ \"schemaVersion\": 1, \"history\": [] }"),
                Arguments.of("entries 가 배열이 아님", "{ \"schemaVersion\": 1, \"history\": { \"entries\": {} } }"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("brokenSkeletons")
    @DisplayName("뼈대가 틀린 v1 파일은 손상이다 — .bak 이 없으면 옆으로 옮기고 빈 이력으로 시작한다")
    void brokenSkeletonIsCorrupt(String caseName, String content) throws IOException {
        // given
        write(file(), content);

        // when
        LoadedHistory result = loaded(repository().load());

        // then
        String moved = "state.json.corrupt-20261126T010203Z";
        assertThat(result.history()).isEqualTo(WorkHistory.empty(HistoryPolicy.DEFAULT));
        assertThat(result.notices()).containsExactly(new HistoryLoadNotice.CorruptMovedAside(moved));
        assertThat(result.access()).isEqualTo(StoreAccess.WRITABLE);
        assertThat(file()).doesNotExist();
        assertThat(Files.readString(dir.resolve(moved), StandardCharsets.UTF_8)).isEqualTo(content);
    }

    @Test
    @DisplayName("state.json 과 .bak 이 둘 다 손상이고 원본을 옮기지 못하면 Io 오류이고 원본은 그대로다")
    void bothCorruptAndMoveFailsIsIo() throws IOException {
        // given
        write(file(), fixture("v1-corrupt.json"));
        JsonStateRepository repository = repositoryMovingWith((from, to) -> {
            throw new IOException("옮기기 거부");
        });

        // when
        Result<LoadedHistory, HistoryStoreError> result = repository.load();

        // then
        assertThat(result.error()).containsInstanceOf(HistoryStoreError.Io.class);
        assertThat(Files.readString(file(), StandardCharsets.UTF_8)).isEqualTo(fixture("v1-corrupt.json"));
        assertThat(corruptFiles()).isEmpty();
    }

    @Test
    @DisplayName(".bak 으로 복구하면 손상 원본을 state.json.corrupt-UTC 로 복사해 두고 그 이름을 알리며, 첫 저장 뒤에도 남는다")
    void recoveryFromBackupKeepsCorruptOriginalCopy() throws IOException {
        // given
        write(file(), fixture("v1-corrupt.json"));
        write(dir.resolve("state.json.bak"), fixture("v1-history.json"));
        JsonStateRepository repository = repository();

        // when
        LoadedHistory result = loaded(repository.load());
        repository.save(historyOf("C:\\new"));

        // then
        String moved = "state.json.corrupt-20261126T010203Z";
        assertThat(result.notices()).containsExactly(new HistoryLoadNotice.RecoveredFromBackup(Optional.of(moved)));
        assertThat(Files.readString(dir.resolve(moved), StandardCharsets.UTF_8)).isEqualTo(fixture("v1-corrupt.json"));
    }

    @Test
    @DisplayName(".bak 으로 복구하면서 손상 원본을 복사해 두지 못하면 Io 오류이고 원본은 그대로다")
    void recoveryFromBackupFailsWhenOriginalCannotBeCopied() throws IOException {
        // given
        write(file(), fixture("v1-corrupt.json"));
        write(dir.resolve("state.json.bak"), fixture("v1-history.json"));
        JsonStateRepository repository = repositoryMovingWith((from, to) -> {
            throw new IOException("옮기기 거부");
        });

        // when
        Result<LoadedHistory, HistoryStoreError> result = repository.load();

        // then
        assertThat(result.error()).containsInstanceOf(HistoryStoreError.Io.class);
        assertThat(Files.readString(file(), StandardCharsets.UTF_8)).isEqualTo(fixture("v1-corrupt.json"));
        assertThat(repository.save(historyOf("C:\\new")).error()).containsInstanceOf(HistoryStoreError.ReadOnly.class);
    }

    @Test
    @DisplayName("옮길 이름이 이미 있으면 -1, -2 를 붙여 겹치지 않게 옮긴다")
    void corruptNameCollisionGetsNumberSuffix() throws IOException {
        // given
        write(file(), fixture("v1-corrupt.json"));
        write(dir.resolve("state.json.corrupt-20261126T010203Z"), "earlier");
        write(dir.resolve("state.json.corrupt-20261126T010203Z-1"), "earlier too");

        // when
        LoadedHistory result = loaded(repository().load());

        // then
        assertThat(result.notices())
                .containsExactly(new HistoryLoadNotice.CorruptMovedAside("state.json.corrupt-20261126T010203Z-2"));
        assertThat(Files.readString(dir.resolve("state.json.corrupt-20261126T010203Z"), StandardCharsets.UTF_8))
                .isEqualTo("earlier");
        assertThat(Files.readString(dir.resolve("state.json.corrupt-20261126T010203Z-2"), StandardCharsets.UTF_8))
                .isEqualTo(fixture("v1-corrupt.json"));
    }

    @Test
    @DisplayName(".bak 으로 복구하면 state.json 을 .bak 내용으로 되돌려 써서, 저장 없이 닫아도 다음 실행이 같은 이력을 읽는다")
    void recoveryRestoresStateFileSoNextRunWithoutSaveKeepsHistory() throws IOException {
        // given
        write(file(), fixture("v1-corrupt.json"));
        write(dir.resolve("state.json.bak"), fixture("v1-history.json"));
        JsonStateRepository run1 = repository();
        loaded(run1.load());
        run1.close();

        // when
        LoadedHistory run2 = loaded(repository().load());

        // then
        assertThat(run2.history().entries()).hasSize(2);
        assertThat(run2.notices()).isEmpty();
        assertThat(run2.access()).isEqualTo(StoreAccess.WRITABLE);
    }

    @Test
    @DisplayName(".bak 으로 복구하고 저장한 뒤 다음 실행이 다시 저장해도 원래 이력은 state.json 과 .bak 에 남는다")
    void recoveredHistorySurvivesSaveAndNextRunSave() throws IOException {
        // given
        write(file(), fixture("v1-corrupt.json"));
        write(dir.resolve("state.json.bak"), fixture("v1-history.json"));
        JsonStateRepository run1 = repository();
        LoadedHistory recovered = loaded(run1.load());
        run1.save(recovered.history().recordVisit(path(WINDOWS, "C:\\work\\first"), NOW));
        run1.close();

        // when
        JsonStateRepository run2 = repository();
        LoadedHistory second = loaded(run2.load());
        run2.save(second.history().recordVisit(path(WINDOWS, "C:\\work\\second"), NOW));
        run2.close();

        // then
        WorkspacePath tanager = path(UBUNTU, "/mnt/c/Users/user/Desktop/Tanager");
        WorkspacePath infra = path(WINDOWS, "C:\\work\\infra");
        WorkHistory inState = loaded(repository().load()).history();
        Path copy = Files.createDirectories(dir.resolve("copy")).resolve("state.json");
        Files.copy(dir.resolve("state.json.bak"), copy);
        WorkHistory inBackup = loaded(repositoryAt(copy).load()).history();
        assertThat(inState.entries()).containsKeys(tanager, infra);
        assertThat(inBackup.entries()).containsKeys(tanager, infra);
    }

    @Test
    @DisplayName(".bak 으로 복구할 때 손상 원본은 복사해 남기고 state.json 은 어느 단계에서도 없어지지 않는다")
    void recoveryCopiesCorruptOriginalSoStateFileIsNeverMissing() throws IOException {
        // given
        write(file(), fixture("v1-corrupt.json"));
        write(dir.resolve("state.json.bak"), fixture("v1-history.json"));
        List<String> stateDuringCopy = new ArrayList<>();
        JsonStateRepository repository = repositoryCopyingWith((from, to) -> {
            stateDuringCopy.add(Files.readString(file(), StandardCharsets.UTF_8));
            Files.copy(from, to);
            stateDuringCopy.add(Files.readString(file(), StandardCharsets.UTF_8));
        });

        // when
        LoadedHistory result = loaded(repository.load());

        // then
        assertThat(stateDuringCopy).containsExactly(fixture("v1-corrupt.json"), fixture("v1-corrupt.json"));
        assertThat(Files.readString(file(), StandardCharsets.UTF_8)).isEqualTo(fixture("v1-history.json"));
        assertThat(Files.readString(dir.resolve("state.json.corrupt-20261126T010203Z"), StandardCharsets.UTF_8))
                .isEqualTo(fixture("v1-corrupt.json"));
        assertThat(result.notices())
                .containsExactly(
                        new HistoryLoadNotice.RecoveredFromBackup(Optional.of("state.json.corrupt-20261126T010203Z")));
    }

    @Test
    @DisplayName(".bak 으로 복구하면서 손상 원본을 복사하지 못하면 Io 오류이고 아무것도 바뀌지 않는다")
    void recoveryCopyFailureIsIoAndTouchesNothing() throws IOException {
        // given
        write(file(), fixture("v1-corrupt.json"));
        write(dir.resolve("state.json.bak"), fixture("v1-history.json"));
        JsonStateRepository repository = repositoryCopyingWith((from, to) -> {
            throw new IOException("복사 거부");
        });

        // when
        Result<LoadedHistory, HistoryStoreError> result = repository.load();

        // then
        assertThat(result.error()).containsInstanceOf(HistoryStoreError.Io.class);
        assertThat(Files.readString(file(), StandardCharsets.UTF_8)).isEqualTo(fixture("v1-corrupt.json"));
        assertThat(Files.readString(dir.resolve("state.json.bak"), StandardCharsets.UTF_8))
                .isEqualTo(fixture("v1-history.json"));
        assertThat(corruptFiles()).isEmpty();
        assertThat(repository.save(historyOf("C:\\new")).error()).containsInstanceOf(HistoryStoreError.ReadOnly.class);
    }

    @Test
    @DisplayName(".bak 으로 복구하면서 state.json 을 되돌려 쓰지 못하면 Io 오류이고 손상 원본은 제자리, 복사본은 남는다")
    void recoveryRestoreFailureLeavesCorruptOriginalInPlace() throws IOException {
        // given
        write(file(), fixture("v1-corrupt.json"));
        write(dir.resolve("state.json.bak"), fixture("v1-history.json"));
        // state.json 의 임시 파일 자리를 비어 있지 않은 폴더로 막아 되돌려 쓰기를 실패시킨다
        Path blocker = Files.createDirectories(dir.resolve("state.json.tmp"));
        Files.writeString(blocker.resolve("keep"), "x");
        JsonStateRepository repository = repository();

        // when
        Result<LoadedHistory, HistoryStoreError> result = repository.load();

        // then
        assertThat(result.error())
                .get()
                .isInstanceOfSatisfying(
                        HistoryStoreError.Io.class,
                        io -> assertThat(io.detail()).contains("손상된 이력 파일은 제자리에 그대로 있다"));
        assertThat(Files.readString(file(), StandardCharsets.UTF_8)).isEqualTo(fixture("v1-corrupt.json"));
        assertThat(Files.readString(dir.resolve("state.json.corrupt-20261126T010203Z"), StandardCharsets.UTF_8))
                .isEqualTo(fixture("v1-corrupt.json"));
        assertThat(Files.readString(dir.resolve("state.json.bak"), StandardCharsets.UTF_8))
                .isEqualTo(fixture("v1-history.json"));
        assertThat(repository.save(historyOf("C:\\new")).error()).containsInstanceOf(HistoryStoreError.ReadOnly.class);
    }

    @Test
    @DisplayName(".bak 으로 복구할 때도 복사할 이름이 이미 있으면 -1, -2 를 붙이고 있던 파일은 건드리지 않는다")
    void recoveryCopyNameCollisionGetsNumberSuffix() throws IOException {
        // given
        write(file(), fixture("v1-corrupt.json"));
        write(dir.resolve("state.json.bak"), fixture("v1-history.json"));
        write(dir.resolve("state.json.corrupt-20261126T010203Z"), "earlier");
        write(dir.resolve("state.json.corrupt-20261126T010203Z-1"), "earlier too");

        // when
        LoadedHistory result = loaded(repository().load());

        // then
        assertThat(result.notices())
                .containsExactly(new HistoryLoadNotice.RecoveredFromBackup(
                        Optional.of("state.json.corrupt-20261126T010203Z-2")));
        assertThat(Files.readString(dir.resolve("state.json.corrupt-20261126T010203Z"), StandardCharsets.UTF_8))
                .isEqualTo("earlier");
        assertThat(Files.readString(dir.resolve("state.json.corrupt-20261126T010203Z-1"), StandardCharsets.UTF_8))
                .isEqualTo("earlier too");
        assertThat(Files.readString(dir.resolve("state.json.corrupt-20261126T010203Z-2"), StandardCharsets.UTF_8))
                .isEqualTo(fixture("v1-corrupt.json"));
        assertThat(Files.readString(file(), StandardCharsets.UTF_8)).isEqualTo(fixture("v1-history.json"));
    }

    @Test
    @DisplayName("손상이고 .bak 을 읽다 입출력 오류가 나면 아무것도 옮기지 않고 '.bak 을 읽지 못했다' Io 오류다")
    void unreadableBackupIsIoAndTouchesNothing() throws IOException {
        // given
        write(file(), fixture("v1-corrupt.json"));
        // .bak 자리를 폴더로 막아 읽기를 입출력 오류로 만든다
        Files.createDirectories(dir.resolve("state.json.bak"));
        JsonStateRepository repository = repository();

        // when
        Result<LoadedHistory, HistoryStoreError> result = repository.load();

        // then
        assertThat(result.error())
                .get()
                .isInstanceOfSatisfying(
                        HistoryStoreError.Io.class,
                        io -> assertThat(io.detail()).startsWith(".bak 을 읽지 못했다"));
        assertThat(Files.readString(file(), StandardCharsets.UTF_8)).isEqualTo(fixture("v1-corrupt.json"));
        assertThat(dir.resolve("state.json.bak")).isDirectory();
        assertThat(corruptFiles()).isEmpty();
        assertThat(repository.save(historyOf("C:\\new")).error()).containsInstanceOf(HistoryStoreError.ReadOnly.class);
    }

    @Test
    @DisplayName("다른 인스턴스 때문에 손상 원본을 옮기지 못한 load 뒤, 쓰기 가능으로 다시 읽은 첫 저장은 .bak 을 만든다")
    void corruptLoadWithoutMoveDoesNotSuppressLaterBackup() throws IOException {
        // given
        write(file(), fixture("v1-corrupt.json"));
        JsonStateRepository repository = repository();
        try (FileChannel other = FileChannel.open(
                        dir.resolve("state.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock _ = other.lock()) {
            assertThat(loaded(repository.load()).access()).isEqualTo(StoreAccess.READ_ONLY);
        }
        // 다른 인스턴스가 정상 파일을 남기고 끝났다
        write(file(), fixture("v1-history.json"));
        assertThat(loaded(repository.load()).access()).isEqualTo(StoreAccess.WRITABLE);

        // when
        repository.save(historyOf("C:\\new"));

        // then
        assertThat(Files.readString(dir.resolve("state.json.bak"), StandardCharsets.UTF_8))
                .isEqualTo(fixture("v1-history.json"));
    }

    @Test
    @DisplayName("다른 인스턴스 때문에 .bak 으로 복구만 하고 아무것도 바꾸지 못한 load 뒤, 쓰기 가능으로 다시 읽은 첫 저장은 .bak 을 그때의 state.json 으로 바꾼다")
    void recoveryWithoutLockDoesNotSuppressLaterBackup() throws IOException {
        // given: 손상된 state.json · 정상 .bak · 다른 인스턴스의 잠금
        write(file(), fixture("v1-corrupt.json"));
        write(dir.resolve("state.json.bak"), fixture("v1-full.json"));
        JsonStateRepository repository = repository();
        try (FileChannel other = FileChannel.open(
                        dir.resolve("state.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock _ = other.lock()) {
            assertThat(loaded(repository.load()).access()).isEqualTo(StoreAccess.READ_ONLY);
        }
        // 다른 인스턴스가 정상 파일을 남기고 끝났다
        write(file(), fixture("v1-history.json"));
        assertThat(loaded(repository.load()).access()).isEqualTo(StoreAccess.WRITABLE);

        // when
        repository.save(historyOf("C:\\new"));

        // then
        assertThat(Files.readString(dir.resolve("state.json.bak"), StandardCharsets.UTF_8))
                .isEqualTo(fixture("v1-history.json"));
    }

    // ---- 상위 버전 · 잠금 ----

    static Stream<Arguments> reshapedNewerFiles() {
        return Stream.of(
                Arguments.of("history 가 배열 · .bak 있음", "v2-reshaped.json", true),
                Arguments.of("history 가 배열 · .bak 없음", "v2-reshaped.json", false),
                Arguments.of("entries 가 객체 · .bak 있음", "v2-reshaped-entries.json", true),
                Arguments.of("entries 가 객체 · .bak 없음", "v2-reshaped-entries.json", false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("reshapedNewerFiles")
    @DisplayName("상위 버전 파일은 history 모양이 달라도 손상이 아니다 — 항목 0개 · 읽기 전용 · 파일과 .bak 그대로 · 옮기지 않음")
    void newerSchemaWithReshapedHistoryIsReadOnlyNotCorrupt(String caseName, String fixtureName, boolean withBackup)
            throws IOException {
        // given
        write(file(), fixture(fixtureName));
        byte[] before = Files.readAllBytes(file());
        if (withBackup) {
            write(dir.resolve("state.json.bak"), fixture("v1-history.json"));
        }
        JsonStateRepository repository = repository();

        // when
        LoadedHistory result = loaded(repository.load());
        Result<WorkHistory, HistoryStoreError> saved = repository.save(historyOf("C:\\other"));

        // then
        assertThat(result.access()).isEqualTo(StoreAccess.READ_ONLY);
        assertThat(result.notices()).containsExactly(new HistoryLoadNotice.NewerSchema(2));
        assertThat(result.history()).isEqualTo(WorkHistory.empty(HistoryPolicy.DEFAULT));
        assertThat(saved.error()).containsInstanceOf(HistoryStoreError.ReadOnly.class);
        assertThat(Files.readAllBytes(file())).isEqualTo(before);
        assertThat(corruptFiles()).isEmpty();
        if (withBackup) {
            assertThat(Files.readString(dir.resolve("state.json.bak"), StandardCharsets.UTF_8))
                    .isEqualTo(fixture("v1-history.json"));
        }
    }

    @Test
    @DisplayName("state.lock 을 열 수 없어도 state.json 을 읽을 수 있으면 읽기 전용으로 열고 LockUnavailable 을 알린다")
    void unopenableLockFileOpensReadOnly() throws IOException {
        // given
        write(file(), fixture("v1-history.json"));
        byte[] before = Files.readAllBytes(file());
        // 잠금 파일 자리를 폴더로 막아 열기를 실패시킨다
        Files.createDirectories(dir.resolve("state.lock"));
        JsonStateRepository repository = repository();

        // when
        LoadedHistory result = loaded(repository.load());
        Result<WorkHistory, HistoryStoreError> saved = repository.save(historyOf("C:\\other"));

        // then
        assertThat(result.history().entries()).hasSize(2);
        assertThat(result.access()).isEqualTo(StoreAccess.READ_ONLY);
        assertThat(result.notices()).singleElement().isInstanceOf(HistoryLoadNotice.LockUnavailable.class);
        assertThat(saved.error()).containsInstanceOf(HistoryStoreError.ReadOnly.class);
        assertThat(Files.readAllBytes(file())).isEqualTo(before);
    }

    @Test
    @DisplayName("schemaVersion 2 는 읽을 수 있는 만큼 읽고 읽기 전용이며 저장은 ReadOnly 이고 파일은 그대로다")
    void newerSchemaOpensReadOnly() throws IOException {
        // given
        write(file(), fixture("v2-newer.json"));
        byte[] before = Files.readAllBytes(file());
        JsonStateRepository repository = repository();

        // when
        LoadedHistory result = loaded(repository.load());
        Result<WorkHistory, HistoryStoreError> saved = repository.save(historyOf("C:\\other"));

        // then
        assertThat(result.history().find(path(WINDOWS, "C:\\work\\infra"))).isPresent();
        assertThat(result.access()).isEqualTo(StoreAccess.READ_ONLY);
        assertThat(result.notices()).containsExactly(new HistoryLoadNotice.NewerSchema(2));
        assertThat(saved.error()).containsInstanceOf(HistoryStoreError.ReadOnly.class);
        assertThat(Files.readAllBytes(file())).isEqualTo(before);
        assertThat(dir.resolve("state.json.bak")).doesNotExist();
    }

    static Stream<Arguments> integralNewerVersions() {
        return Stream.of(
                Arguments.of("int 범위를 넘는 정수", "3000000000", 3_000_000_000L),
                Arguments.of("정수 값인 소수", "2.0", 2L),
                Arguments.of("long 범위를 넘는 정수", "100000000000000000000000000000", Long.MAX_VALUE),
                Arguments.of("long 범위를 넘는 정수 값인 소수", "1e30", Long.MAX_VALUE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("integralNewerVersions")
    @DisplayName("정수 값인 schemaVersion 이 1 보다 크면 형식과 범위에 상관없이 상위 버전이다 — 읽기 전용 · 파일 그대로 · 옮기지 않음")
    void integralVersionAboveOneIsNewerSchema(String caseName, String version, long expected) throws IOException {
        // given
        String content = "{ \"schemaVersion\": " + version + ", \"history\": { \"entries\": ["
                + entryJson("{ \"kind\": \"windows\" }", "C:\\work\\infra", 5) + "] } }";
        write(file(), content);
        JsonStateRepository repository = repository();

        // when
        LoadedHistory result = loaded(repository.load());
        Result<WorkHistory, HistoryStoreError> saved = repository.save(historyOf("C:\\other"));

        // then
        assertThat(result.access()).isEqualTo(StoreAccess.READ_ONLY);
        assertThat(result.notices()).containsExactly(new HistoryLoadNotice.NewerSchema(expected));
        assertThat(result.history().find(path(WINDOWS, "C:\\work\\infra"))).isPresent();
        assertThat(saved.error()).containsInstanceOf(HistoryStoreError.ReadOnly.class);
        assertThat(Files.readString(file(), StandardCharsets.UTF_8)).isEqualTo(content);
        assertThat(corruptFiles()).isEmpty();
    }

    @Test
    @DisplayName("schemaVersion 1.0 은 1 로 읽고 쓰기 가능하다")
    void integralFloatVersionOneIsCurrentSchema() throws IOException {
        // given
        write(
                file(),
                "{ \"schemaVersion\": 1.0, \"history\": { \"entries\": ["
                        + entryJson("{ \"kind\": \"windows\" }", "C:\\work\\infra", 5) + "] } }");

        // when
        LoadedHistory result = loaded(repository().load());

        // then
        assertThat(result.access()).isEqualTo(StoreAccess.WRITABLE);
        assertThat(result.notices()).isEmpty();
        assertThat(result.history().entries()).hasSize(1);
    }

    @Test
    @DisplayName("다른 인스턴스가 state.lock 을 잡고 있으면 읽기 전용이고 저장은 ReadOnly 다")
    void lockedByOtherInstanceOpensReadOnly() throws IOException {
        // given
        write(file(), fixture("v1-history.json"));
        byte[] before = Files.readAllBytes(file());
        try (FileChannel other = FileChannel.open(
                        dir.resolve("state.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock _ = other.lock()) {
            JsonStateRepository repository = repository();

            // when
            LoadedHistory result = loaded(repository.load());
            Result<WorkHistory, HistoryStoreError> saved = repository.save(historyOf("C:\\other"));

            // then
            assertThat(result.history().entries()).hasSize(2);
            assertThat(result.access()).isEqualTo(StoreAccess.READ_ONLY);
            assertThat(result.notices()).containsExactly(new HistoryLoadNotice.LockedByOtherInstance());
            assertThat(saved.error()).containsInstanceOf(HistoryStoreError.ReadOnly.class);
            assertThat(Files.readAllBytes(file())).isEqualTo(before);
        }
    }

    @Test
    @DisplayName("다른 프로세스(자식 JVM)가 state.lock 을 쥐고 있으면 읽기 전용이고 LockedByOtherInstance 다")
    void lockHeldByAnotherProcessOpensReadOnly() throws Exception {
        // given
        write(file(), fixture("v1-history.json"));
        byte[] before = Files.readAllBytes(file());
        Process holder = LockHolder.start(dir.resolve("state.lock"));
        try {
            JsonStateRepository repository = repository();

            // when
            LoadedHistory result = loaded(repository.load());
            Result<WorkHistory, HistoryStoreError> saved = repository.save(historyOf("C:\\other"));

            // then
            assertThat(result.history().entries()).hasSize(2);
            assertThat(result.access()).isEqualTo(StoreAccess.READ_ONLY);
            assertThat(result.notices()).containsExactly(new HistoryLoadNotice.LockedByOtherInstance());
            assertThat(saved.error()).containsInstanceOf(HistoryStoreError.ReadOnly.class);
            assertThat(Files.readAllBytes(file())).isEqualTo(before);
        } finally {
            LockHolder.stop(holder);
        }
    }

    @Test
    @DisplayName("다른 인스턴스가 잠금을 쥐고 파일이 손상이면 옮기지 않고 그대로 둔다")
    void corruptFileLockedElsewhereIsNotMoved() throws IOException {
        // given
        write(file(), fixture("v1-corrupt.json"));
        try (FileChannel other = FileChannel.open(
                        dir.resolve("state.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock _ = other.lock()) {

            // when
            LoadedHistory result = loaded(repository().load());

            // then
            assertThat(result.history()).isEqualTo(WorkHistory.empty(HistoryPolicy.DEFAULT));
            assertThat(result.access()).isEqualTo(StoreAccess.READ_ONLY);
            assertThat(result.notices()).containsExactly(new HistoryLoadNotice.LockedByOtherInstance());
            assertThat(Files.readString(file(), StandardCharsets.UTF_8)).isEqualTo(fixture("v1-corrupt.json"));
            assertThat(corruptFiles()).isEmpty();
        }
    }

    @Test
    @DisplayName("잠금 파일을 열 수 없고 파일이 손상이면 .bak 으로 읽되 원본은 옮기지 않는다")
    void corruptFileWithUnavailableLockRecoversWithoutMoving() throws IOException {
        // given
        write(file(), fixture("v1-corrupt.json"));
        write(dir.resolve("state.json.bak"), fixture("v1-history.json"));
        Files.createDirectories(dir.resolve("state.lock"));

        // when
        LoadedHistory result = loaded(repository().load());

        // then
        assertThat(result.history().entries()).hasSize(2);
        assertThat(result.access()).isEqualTo(StoreAccess.READ_ONLY);
        assertThat(result.notices()).hasSize(2);
        assertThat(result.notices().getFirst()).isEqualTo(new HistoryLoadNotice.RecoveredFromBackup(Optional.empty()));
        assertThat(result.notices().getLast()).isInstanceOf(HistoryLoadNotice.LockUnavailable.class);
        assertThat(Files.readString(file(), StandardCharsets.UTF_8)).isEqualTo(fixture("v1-corrupt.json"));
        assertThat(corruptFiles()).isEmpty();
    }

    @Test
    @DisplayName("같은 파일을 연 두 번째 저장소는 읽기 전용이고, 첫 저장소를 닫으면 잠금이 풀린다")
    void secondRepositoryOnSameFileIsReadOnlyUntilFirstCloses() {
        // 같은 JVM 안의 잠금 표(OverlappingFileLockException) 수준만 검증한다. POSIX 에서는 한 프로세스가 채널 하나를 닫으면 그
        // 프로세스의 잠금이 모두 풀리므로, 같은 프로세스에 저장소 둘을 두는 것은 지원하지 않는다(ADR-0013). 프로세스 사이는
        // lockHeldByAnotherProcessOpensReadOnly 가 본다.

        // given
        JsonStateRepository first = repository();
        assertThat(loaded(first.load()).access()).isEqualTo(StoreAccess.WRITABLE);

        // when
        LoadedHistory second = loaded(repository().load());
        first.close();
        LoadedHistory third = loaded(repository().load());

        // then
        assertThat(second.access()).isEqualTo(StoreAccess.READ_ONLY);
        assertThat(second.notices()).containsExactly(new HistoryLoadNotice.LockedByOtherInstance());
        assertThat(third.access()).isEqualTo(StoreAccess.WRITABLE);
    }

    // ---- 열기 전 · 닫은 뒤 · 불러오기 실패 ----

    @Test
    @DisplayName("닫은 뒤의 저장은 '닫힌 저장소에는 쓰지 않는다' ReadOnly 이고 파일은 그대로다")
    void saveAfterCloseIsReadOnlyWithClosedReason() throws IOException {
        // given
        write(file(), fixture("v1-history.json"));
        byte[] before = Files.readAllBytes(file());
        JsonStateRepository repository = repository();
        repository.load();
        repository.close();

        // when
        Result<WorkHistory, HistoryStoreError> saved = repository.save(historyOf("C:\\other"));

        // then
        assertThat(saved.error()).contains(new HistoryStoreError.ReadOnly(HistoryStoreError.ReadOnlyReason.CLOSED));
        assertThat(Files.readAllBytes(file())).isEqualTo(before);
    }

    @Test
    @DisplayName("쓰기 가능으로 연 뒤 다시 불러오기가 실패하면 저장은 ReadOnly 다")
    void saveAfterFailedReloadIsReadOnly() throws IOException {
        // given
        JsonStateRepository repository = repository();
        assertThat(loaded(repository.load()).access()).isEqualTo(StoreAccess.WRITABLE);
        // state.json 자리를 폴더로 막아 읽기를 실패시킨다
        Files.createDirectories(file());
        assertThat(repository.load().error()).containsInstanceOf(HistoryStoreError.Io.class);

        // when
        Result<WorkHistory, HistoryStoreError> saved = repository.save(historyOf("C:\\other"));

        // then
        assertThat(saved.error()).containsInstanceOf(HistoryStoreError.ReadOnly.class);
        assertThat(file()).isDirectory();
    }

    @Test
    @DisplayName("불러오기 전의 저장은 '불러오기 전에는 쓰지 않는다' ReadOnly 이고 파일을 만들지 않는다")
    void saveBeforeLoadIsReadOnlyAndCreatesNothing() {
        // given
        JsonStateRepository repository = repository();

        // when
        Result<WorkHistory, HistoryStoreError> saved = repository.save(historyOf("C:\\other"));

        // then
        assertThat(saved.error()).contains(new HistoryStoreError.ReadOnly(HistoryStoreError.ReadOnlyReason.NOT_LOADED));
        assertThat(file()).doesNotExist();
    }

    // ---- 고쳐 읽기 ----

    static Stream<Arguments> repairableEntries() {
        String base = "\"env\": { \"kind\": \"windows\" }, \"path\": \"C:\\\\work\\\\fix\", \"visitCount\": 3";
        String times = "\"firstVisited\": \"2026-11-21T01:00:00Z\", \"lastVisited\": \"2026-11-23T08:30:00Z\"";
        String flags = "\"pinned\": false, \"hidden\": false";
        Instant first = Instant.parse("2026-11-21T01:00:00Z");
        Instant last = Instant.parse("2026-11-23T08:30:00Z");
        Optional<Alias> none = Optional.empty();
        return Stream.of(
                Arguments.of(
                        "고정이면서 숨김 → 고정 우선",
                        "{" + base + ", " + times + ", \"pinned\": true, \"hidden\": true, \"alias\": null }",
                        new VisitedDirectory(path(WINDOWS, "C:\\work\\fix"), first, last, 3, true, none, false)),
                Arguments.of(
                        "처음 > 마지막 → 처음 = 마지막",
                        "{" + base + ", \"firstVisited\": \"2026-11-23T08:30:00Z\","
                                + " \"lastVisited\": \"2026-11-21T01:00:00Z\", " + flags + ", \"alias\": null }",
                        new VisitedDirectory(path(WINDOWS, "C:\\work\\fix"), first, first, 3, false, none, false)),
                Arguments.of(
                        "공백뿐인 별칭 → 별칭만 버림",
                        "{" + base + ", " + times + ", " + flags + ", \"alias\": \"   \" }",
                        new VisitedDirectory(path(WINDOWS, "C:\\work\\fix"), first, last, 3, false, none, false)),
                Arguments.of(
                        "문자열이 아닌 별칭 → 별칭만 버림",
                        "{" + base + ", " + times + ", " + flags + ", \"alias\": 42 }",
                        new VisitedDirectory(path(WINDOWS, "C:\\work\\fix"), first, last, 3, false, none, false)),
                Arguments.of(
                        "제어 문자가 든 별칭 → 별칭만 버림",
                        "{" + base + ", " + times + ", " + flags + ", \"alias\": \"a\\u0007b\" }",
                        new VisitedDirectory(path(WINDOWS, "C:\\work\\fix"), first, last, 3, false, none, false)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("repairableEntries")
    @DisplayName("고칠 수 있는 불변식 위반 항목은 건너뛰지 않고 고쳐서 읽는다")
    void repairableEntryIsFixedNotSkipped(String caseName, String entry, VisitedDirectory expected) throws IOException {
        // given
        write(file(), stateWith(entryJson("{ \"kind\": \"windows\" }", "C:\\work\\good", 4), entry));

        // when
        LoadedHistory result = loaded(repository().load());

        // then
        assertThat(result.history().entries()).hasSize(2);
        assertThat(result.history().find(path(WINDOWS, "C:\\work\\fix"))).contains(expected);
        assertThat(result.notices()).isEmpty();
        assertThat(result.access()).isEqualTo(StoreAccess.WRITABLE);
    }

    // ---- 쓰기 ----

    @Test
    @DisplayName("읽은 항목의 sources 는 저장해도 그대로이고 새 항목은 [shell] 이다")
    void sourcesSurviveRoundTrip() throws IOException {
        // given
        write(file(), fixture("v1-history.json"));
        JsonStateRepository repository = repository();
        LoadedHistory result = loaded(repository.load());

        // when
        repository.save(result.history().recordVisit(path(WINDOWS, "C:\\work\\new"), NOW));

        // then
        JsonNode after = JSON.readTree(Files.readAllBytes(file()));
        assertThat(entryAt(after, "/mnt/c/Users/user/Desktop/Tanager").get("sources"))
                .isEqualTo(JSON.readTree("[\"shell\",\"claude-code\"]"));
        assertThat(entryAt(after, "C:\\work\\infra").get("sources")).isEqualTo(JSON.readTree("[\"shell\"]"));
        assertThat(entryAt(after, "C:\\work\\new").get("sources")).isEqualTo(JSON.readTree("[\"shell\"]"));
    }

    @Test
    @DisplayName("대소문자만 다른 항목의 sources 는 먼저 나온 순서대로 겹치지 않게 합쳐 저장한다")
    void caseOnlyDuplicatesUnionSourcesInFirstSeenOrder() throws IOException {
        // given
        write(
                file(),
                stateWith(
                        entryWithSources("C:\\Work", Optional.of("[\"shell\",\"claude-code\"]")),
                        entryWithSources("c:\\work", Optional.of("[\"claude-code\",\"x\"]"))));
        JsonStateRepository repository = repository();
        LoadedHistory result = loaded(repository.load());

        // when
        repository.save(result.history());

        // then
        JsonNode entries =
                JSON.readTree(Files.readAllBytes(file())).get("history").get("entries");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).get("sources")).isEqualTo(JSON.readTree("[\"shell\",\"claude-code\",\"x\"]"));
    }

    static Stream<Arguments> mergedSourcesWithMissingSide() {
        return Stream.of(
                Arguments.of(
                        "앞 항목에 sources 없음",
                        Optional.empty(),
                        Optional.of("[\"claude-code\"]"),
                        "[\"shell\",\"claude-code\"]"),
                Arguments.of(
                        "뒤 항목에 sources 없음",
                        Optional.of("[\"claude-code\"]"),
                        Optional.empty(),
                        "[\"claude-code\",\"shell\"]"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mergedSourcesWithMissingSide")
    @DisplayName("대소문자만 다른 항목을 합칠 때 sources 가 없는 쪽은 [shell] 로 보고 합친다")
    void mergedSourcesTreatMissingSideAsShell(
            String caseName, Optional<String> firstSources, Optional<String> laterSources, String expected)
            throws IOException {
        // given
        write(
                file(),
                stateWith(entryWithSources("C:\\Work", firstSources), entryWithSources("c:\\work", laterSources)));
        JsonStateRepository repository = repository();
        LoadedHistory result = loaded(repository.load());

        // when
        repository.save(result.history());

        // then
        JsonNode entries =
                JSON.readTree(Files.readAllBytes(file())).get("history").get("entries");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).get("sources")).isEqualTo(JSON.readTree(expected));
    }

    @Test
    @DisplayName("저장하면 SDD 9.2 형식(schemaVersion 1, history.entries)으로 쓴다")
    void saveWritesSddFormat() throws IOException {
        // given
        JsonStateRepository repository = repository();
        repository.load();
        WorkHistory history = WorkHistory.restore(
                List.of(
                        new VisitedDirectory(
                                path(UBUNTU, "/home/me/proj"),
                                Instant.parse("2026-11-20T09:12:03Z"),
                                Instant.parse("2026-11-24T11:05:41Z"),
                                37,
                                true,
                                Optional.of(new Alias("프로젝트")),
                                false),
                        new VisitedDirectory(
                                path(WINDOWS, "C:\\work\\infra"),
                                Instant.parse("2026-11-21T01:00:00Z"),
                                Instant.parse("2026-11-23T08:30:00Z"),
                                5,
                                false,
                                Optional.empty(),
                                true)),
                HistoryPolicy.DEFAULT);

        // when
        repository.save(history);

        // then
        JsonNode root = JSON.readTree(Files.readAllBytes(file()));
        assertThat(root.get("schemaVersion").intValue()).isEqualTo(1);
        JsonNode wsl = entryAt(root, "/home/me/proj");
        assertThat(wsl.get("env")).isEqualTo(JSON.readTree("{\"kind\":\"wsl\",\"distro\":\"Ubuntu-24.04\"}"));
        assertThat(wsl.get("firstVisited").stringValue()).isEqualTo("2026-11-20T09:12:03Z");
        assertThat(wsl.get("lastVisited").stringValue()).isEqualTo("2026-11-24T11:05:41Z");
        assertThat(wsl.get("visitCount").intValue()).isEqualTo(37);
        assertThat(wsl.get("pinned").booleanValue()).isTrue();
        assertThat(wsl.get("alias").stringValue()).isEqualTo("프로젝트");
        assertThat(wsl.get("hidden").booleanValue()).isFalse();
        assertThat(wsl.get("sources")).isEqualTo(JSON.readTree("[\"shell\"]"));
        JsonNode win = entryAt(root, "C:\\work\\infra");
        assertThat(win.get("env")).isEqualTo(JSON.readTree("{\"kind\":\"windows\"}"));
        assertThat(win.get("alias").isNull()).isTrue();
        assertThat(win.get("hidden").booleanValue()).isTrue();
    }

    private static JsonNode entryAt(JsonNode root, String path) {
        for (JsonNode entry : root.get("history").get("entries")) {
            if (entry.get("path").stringValue().equals(path)) {
                return entry;
            }
        }
        throw new AssertionError("항목이 없다: " + path);
    }

    @Test
    @DisplayName("workspace · window · 모르는 최상위 키는 읽고 저장해도 그대로 남는다")
    void unknownTopLevelKeysSurviveLoadThenSave() throws IOException {
        // given
        write(file(), fixture("v1-full.json"));
        JsonNode original = JSON.readTree(fixture("v1-full.json"));
        JsonStateRepository repository = repository();
        LoadedHistory result = loaded(repository.load());

        // when
        repository.save(result.history().recordVisit(path(WINDOWS, "C:\\work\\new"), NOW));

        // then
        JsonNode after = JSON.readTree(Files.readAllBytes(file()));
        assertThat(after.get("workspace")).isEqualTo(original.get("workspace"));
        assertThat(after.get("window")).isEqualTo(original.get("window"));
        assertThat(after.get("futureFeature")).isEqualTo(original.get("futureFeature"));
        assertThat(after.get("schemaVersion").intValue()).isEqualTo(1);
        assertThat(after.get("history").get("entries")).hasSize(3);
    }

    @Test
    @DisplayName("저장 도중 임시 파일을 쓰지 못하면 Io 오류이고 기존 state.json 은 그대로다")
    void tmpWriteFailureLeavesExistingFileIntact() throws IOException {
        // given
        write(file(), fixture("v1-history.json"));
        byte[] before = Files.readAllBytes(file());
        JsonStateRepository repository = repository();
        repository.load();
        // 임시 파일 자리를 비어 있지 않은 폴더로 막아 쓰기를 실패시킨다
        Path blocker = Files.createDirectories(dir.resolve("state.json.tmp"));
        Files.writeString(blocker.resolve("keep"), "x");

        // when
        Result<WorkHistory, HistoryStoreError> saved = repository.save(historyOf("C:\\other"));

        // then
        assertThat(saved.error()).containsInstanceOf(HistoryStoreError.Io.class);
        assertThat(Files.readAllBytes(file())).isEqualTo(before);
    }

    @Test
    @DisplayName(".bak 을 쓰지 못해도 저장은 계속된다")
    void backupFailureDoesNotStopSave() throws IOException {
        // given
        write(file(), fixture("v1-history.json"));
        JsonStateRepository repository = repository();
        repository.load();
        // .bak 의 임시 파일 자리를 비어 있지 않은 폴더로 막아 백업 쓰기를 실패시킨다
        Path blocker = Files.createDirectories(dir.resolve("state.json.bak.tmp"));
        Files.writeString(blocker.resolve("keep"), "x");

        // when
        Result<WorkHistory, HistoryStoreError> saved = repository.save(historyOf("C:\\first"));

        // then
        assertThat(saved.value()).contains(historyOf("C:\\first"));
        assertThat(dir.resolve("state.json.bak")).doesNotExist();
        assertThat(entryAt(JSON.readTree(Files.readAllBytes(file())), "C:\\first"))
                .isNotNull();
    }

    @Test
    @DisplayName("저장한 파일에는 OS 와 상관없이 CR 이 없다")
    void savedFileHasNoCarriageReturn() throws IOException {
        // given
        write(file(), fixture("v1-full.json"));
        JsonStateRepository repository = repository();
        LoadedHistory result = loaded(repository.load());

        // when
        repository.save(result.history());

        // then
        assertThat(new String(Files.readAllBytes(file()), StandardCharsets.UTF_8))
                .contains("\n")
                .doesNotContain("\r");
    }

    @Test
    @DisplayName("이번 실행의 첫 저장 직전에 state.json 이 .bak 이 되고 두 번째 저장은 .bak 을 바꾸지 않는다")
    void backupIsTakenOnlyBeforeFirstSavePerRun() throws IOException {
        // given
        write(file(), fixture("v1-history.json"));
        JsonStateRepository repository = repository();
        repository.load();
        Path backup = dir.resolve("state.json.bak");
        assertThat(backup).doesNotExist();

        // when
        repository.save(historyOf("C:\\first"));
        byte[] firstSaveOutput = Files.readAllBytes(file());
        repository.save(historyOf("C:\\second"));

        // then
        assertThat(Files.readString(backup, StandardCharsets.UTF_8)).isEqualTo(fixture("v1-history.json"));
        assertThat(Files.readAllBytes(file())).isNotEqualTo(firstSaveOutput);
    }

    @Test
    @DisplayName("다음 실행(새 저장소)의 첫 저장은 .bak 을 그때의 state.json 으로 갱신한다")
    void nextRunRefreshesBackup() throws IOException {
        // given
        write(file(), fixture("v1-history.json"));
        JsonStateRepository run1 = repository();
        run1.load();
        run1.save(historyOf("C:\\first"));
        byte[] afterRun1 = Files.readAllBytes(file());
        run1.close();

        // when
        JsonStateRepository run2 = repository();
        run2.load();
        run2.save(historyOf("C:\\second"));

        // then
        assertThat(Files.readAllBytes(dir.resolve("state.json.bak"))).isEqualTo(afterRun1);
    }

    @Test
    @DisplayName("기존 state.json 이 없던 첫 저장은 .bak 을 만들지 않는다")
    void firstSaveWithoutExistingFileMakesNoBackup() {
        // given
        JsonStateRepository repository = repository();
        repository.load();

        // when
        repository.save(historyOf("C:\\first"));

        // then
        assertThat(file()).exists();
        assertThat(dir.resolve("state.json.bak")).doesNotExist();
    }

    @Test
    @DisplayName("한글 · 공백 · # 경로는 왕복해도 그대로이고 파일은 BOM 없는 UTF-8 이다")
    void koreanSpacesAndHashRoundTripAsUtf8WithoutBom() throws IOException {
        // given
        String raw = "/home/me/한글 폴더/프로젝트 #1";
        JsonStateRepository repository = repository();
        repository.load();
        WorkHistory history = WorkHistory.restore(
                List.of(new VisitedDirectory(
                        path(UBUNTU, raw), NOW, NOW, 1, false, Optional.of(new Alias("가나 다")), false)),
                HistoryPolicy.DEFAULT);

        // when
        repository.save(history);
        repository.close();
        LoadedHistory reread = loaded(repositoryAt(file()).load());

        // then
        byte[] bytes = Files.readAllBytes(file());
        assertThat(Arrays.copyOf(bytes, 3)).isNotEqualTo(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        assertThat(new String(bytes, StandardCharsets.UTF_8)).contains(raw).contains("가나 다");
        assertThat(reread.history()).isEqualTo(history);
    }

    // ---- 속성 ----

    @Property(tries = 60)
    @Label("어떤 이력이든 저장했다가 새 저장소로 읽으면 같은 이력이다")
    void anyHistoryRoundTrips(@ForAll("histories") WorkHistory history) throws IOException {
        Path tmp = Files.createTempDirectory("tanager-state-prop");
        try {
            // given
            Path state = tmp.resolve("state.json");
            try (JsonStateRepository writer =
                    new JsonStateRepository(state, HistoryPolicy.DEFAULT, InstantSource.fixed(NOW))) {
                writer.load();

                // when
                writer.save(history);
            }

            // then
            try (JsonStateRepository reader =
                    new JsonStateRepository(state, HistoryPolicy.DEFAULT, InstantSource.fixed(NOW))) {
                LoadedHistory result = loaded(reader.load());
                assertThat(result.history()).isEqualTo(history);
                assertThat(result.notices()).isEmpty();
            }
        } finally {
            try (Stream<Path> walk = Files.walk(tmp)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Provide
    Arbitrary<WorkHistory> histories() {
        return entries().list().ofMaxSize(8).map(list -> WorkHistory.restore(list, HistoryPolicy.DEFAULT));
    }

    private static Arbitrary<String> segment() {
        // 앞뒤는 글자 — 공백 · '.' 정규화와 부딪히지 않게. 가운데에 공백 · # · 한글을 섞는다
        Arbitrary<String> middle = Arbitraries.strings()
                .withChars("abc XYZ#_-019")
                .withCharRange('가', '힣')
                .ofMaxLength(8);
        return middle.map(m -> "a" + m + "z");
    }

    private static Arbitrary<VisitedDirectory> entries() {
        Arbitrary<WorkspacePath> paths = Arbitraries.oneOf(
                segments().map(s -> path(WINDOWS, "C:\\" + String.join("\\", s))),
                segments().map(s -> path(WINDOWS, "\\\\srv\\share\\" + String.join("\\", s))),
                segments().map(s -> path(UBUNTU, "/" + String.join("/", s))),
                segments().map(s -> path(new ShellEnvironment.Wsl("Debian"), "/" + String.join("/", s))));
        Arbitrary<Instant> first =
                Arbitraries.longs().between(1_500_000_000L, 1_900_000_000L).map(Instant::ofEpochSecond);
        Arbitrary<Long> span = Arbitraries.longs().between(0, 100_000_000L);
        Arbitrary<Optional<Alias>> aliases = Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('가', '힣')
                .ofMinLength(1)
                .ofMaxLength(20)
                .map(s -> Optional.of(new Alias(s)))
                .flatMap(a -> Arbitraries.of(a, Optional.<Alias>empty()));
        Arbitrary<Integer> flags = Arbitraries.integers().between(0, 2); // 0 일반 · 1 고정 · 2 숨김
        return Combinators.combine(paths, first, span, Arbitraries.integers().between(1, 100_000), aliases, flags)
                .as((p, f, s, count, alias, flag) ->
                        new VisitedDirectory(p, f, f.plusSeconds(s), count, flag == 1, alias, flag == 2));
    }

    private static Arbitrary<List<String>> segments() {
        return segment().list().ofMinSize(0).ofMaxSize(4);
    }
}
