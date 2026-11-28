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
import java.util.Optional;
import java.util.stream.Stream;
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
import tanager.application.port.out.LoadedWorkspace;
import tanager.application.port.out.StoreAccess;
import tanager.application.port.out.WorkspaceLoadNotice;
import tanager.domain.history.HistoryPolicy;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.VisitedDirectory;
import tanager.domain.history.WorkHistory;
import tanager.domain.history.WorkspacePath;
import tanager.domain.layout.LayoutNode;
import tanager.domain.layout.Orientation;
import tanager.domain.layout.Ratio;
import tanager.domain.session.ProfileId;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.Result;
import tanager.domain.shared.TabId;
import tanager.domain.workspace.Bounds;
import tanager.domain.workspace.PaneSpec;
import tanager.domain.workspace.PanelState;
import tanager.domain.workspace.Tab;
import tanager.domain.workspace.TabName;
import tanager.domain.workspace.WindowState;
import tanager.domain.workspace.Workspace;
import tanager.domain.workspace.WorkspaceSnapshot;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** state.json v2 의 workspace · window 부분과 마이그레이션 · 부분 손상 · 두 포트가 한 문서를 나눠 쓰기(#178, ADR-0013 개정). */
class JsonStateWorkspaceTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-11-28T01:02:03Z");
    private static final ShellEnvironment WINDOWS = new ShellEnvironment.Windows();
    private static final ShellEnvironment UBUNTU = new ShellEnvironment.Wsl("Ubuntu-24.04");
    private static final PaneId P1 = new PaneId("p1");
    private static final PaneId P2 = new PaneId("p2");
    private static final PaneId P3 = new PaneId("p3");
    private static final PaneId P4 = new PaneId("p4");
    private static final TabId T1 = new TabId("t1");
    private static final TabId T2 = new TabId("t2");
    private static final ProfileId UBUNTU_PROFILE = new ProfileId("wsl:Ubuntu-24.04");
    private static final ProfileId PWSH = new ProfileId("powershell");

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
        try (InputStream in = JsonStateWorkspaceTest.class.getResourceAsStream("/state/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void write(Path target, String text) throws IOException {
        Files.writeString(target, text, StandardCharsets.UTF_8);
    }

    private JsonNode onDisk() throws IOException {
        return JSON.readTree(Files.readAllBytes(file()));
    }

    private List<String> corruptFiles() throws IOException {
        try (Stream<Path> list = Files.list(dir)) {
            return list.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("state.json.corrupt-"))
                    .toList();
        }
    }

    private static WorkspacePath path(ShellEnvironment env, String raw) {
        return WorkspacePath.of(env, raw).value().orElseThrow();
    }

    private static <T> T ok(Result<T, HistoryStoreError> result) {
        return result.value().orElseThrow(() -> new AssertionError("실패: " + result.error()));
    }

    private static LayoutNode leaf(PaneId pane) {
        return new LayoutNode.Leaf(pane);
    }

    private static WorkHistory historyOf(String raw) {
        return WorkHistory.restore(
                List.of(new VisitedDirectory(path(WINDOWS, raw), NOW, NOW, 2, false, Optional.empty(), false)),
                HistoryPolicy.DEFAULT);
    }

    /** 한 탭 [p1 | p2], p1 은 한글 경로 · p2 는 기억한 셸. */
    private static WorkspaceSnapshot snapshot(String dirName, WindowState window) {
        Tab tab = new Tab(T1, new LayoutNode.Split(Orientation.ROW, Ratio.HALF, leaf(P1), leaf(P2)), P1);
        Workspace workspace = Workspace.restore(
                List.of(tab),
                0,
                Map.of(
                        P1,
                        new PaneSpec(P1, Optional.of(UBUNTU_PROFILE), Optional.of(path(UBUNTU, "/home/me/" + dirName))),
                        P2,
                        PaneSpec.remembered(P2)));
        return new WorkspaceSnapshot(workspace, window);
    }

    /** v2 문서: 이력 없음 · workspace 는 주어진 JSON · window 없음. */
    private static String v2With(String workspaceJson) {
        return "{ \"schemaVersion\": 2, \"history\": { \"entries\": [] }, \"workspace\": " + workspaceJson + " }";
    }

    private static String pane(String profileJson, String cwdJson) {
        return "{ \"profile\": " + profileJson + ", \"lastCwd\": " + cwdJson + " }";
    }

    private static final String WIN_CWD = "{ \"env\": { \"kind\": \"windows\" }, \"path\": \"C:\\\\work\" }";

    // ---- 마이그레이션 ----

    @Test
    @DisplayName("v0.4 가 쓴 v1 파일(이력만): 이력은 그대로, 스냅샷은 비고 Migrated(1, 2). 저장하면 schemaVersion 2 이고 history 내용이 같다")
    void historyOnlyV1FileMigrates() throws IOException {
        // given
        write(file(), fixture("v1-history-only.json"));
        JsonNode original = JSON.readTree(fixture("v1-history-only.json"));
        JsonStateRepository repository = repository();

        // when
        LoadedHistory history = ok(repository.load());
        LoadedWorkspace workspace = ok(repository.loadWorkspace());
        ok(repository.save(history.history()));

        // then
        assertThat(history.history().entries()).hasSize(2);
        assertThat(history.notices()).isEmpty();
        assertThat(history.access()).isEqualTo(StoreAccess.WRITABLE);
        assertThat(workspace.snapshot()).isEmpty();
        assertThat(workspace.notices()).containsExactly(new WorkspaceLoadNotice.Migrated(1, 2));
        JsonNode after = onDisk();
        assertThat(after.get("schemaVersion").longValue()).isEqualTo(2);
        // 출처는 id 순으로 쓴다(#207). 그 밖의 history 내용은 같다
        JsonNode expectedHistory = original.get("history").deepCopy();
        ((ObjectNode) expectedHistory.get("entries").get(1))
                .set("sources", JSON.readTree("[\"claude-code\",\"shell\"]"));
        assertThat(after.get("history")).isEqualTo(expectedHistory);
        assertThat(after.has("workspace")).isFalse();
        assertThat(Files.readString(dir.resolve("state.json.bak"), StandardCharsets.UTF_8))
                .isEqualTo(fixture("v1-history-only.json"));
    }

    @Test
    @DisplayName("SDD 9.2 v1 예시: 탭 1개 · 창 3개, p3 의 lastCwd 는 Windows C:\\work\\infra, 포커스 p2, Migrated(1, 2)")
    void sddExampleV1Migrates() throws IOException {
        // given
        write(file(), fixture("v1-sdd-example.json"));

        // when
        LoadedWorkspace loaded = ok(repository().loadWorkspace());

        // then
        WorkspaceSnapshot snapshot = loaded.snapshot().orElseThrow();
        Workspace workspace = snapshot.workspace();
        assertThat(workspace.tabs()).hasSize(1);
        Tab tab = workspace.tab();
        assertThat(tab.id()).isEqualTo(T1);
        assertThat(tab.name()).contains(new TabName("dev"));
        assertThat(tab.focused()).isEqualTo(P2);
        assertThat(tab.zoomed()).isEmpty();
        assertThat(tab.root())
                .isEqualTo(new LayoutNode.Split(
                        Orientation.ROW,
                        Ratio.HALF,
                        leaf(P1),
                        new LayoutNode.Split(Orientation.COLUMN, new Ratio(0.6), leaf(P2), leaf(P3))));
        assertThat(workspace.specs()).hasSize(3);
        assertThat(workspace.spec(P3))
                .contains(new PaneSpec(P3, Optional.of(PWSH), Optional.of(path(WINDOWS, "C:\\work\\infra"))));
        assertThat(workspace.spec(P1))
                .contains(
                        new PaneSpec(P1, Optional.of(UBUNTU_PROFILE), Optional.of(path(UBUNTU, "/home/me/proj/api"))));
        assertThat(workspace.nextPane()).isEqualTo(4);
        assertThat(workspace.nextTab()).isEqualTo(2);
        assertThat(snapshot.window())
                .isEqualTo(new WindowState(
                        Optional.of(new Bounds(120, 80, 1600, 960)),
                        false,
                        new PanelState(true, 280),
                        new PanelState(true, 300)));
        assertThat(loaded.notices()).containsExactly(new WorkspaceLoadNotice.Migrated(1, 2));
        assertThat(loaded.access()).isEqualTo(StoreAccess.WRITABLE);
    }

    @Test
    @DisplayName("v2 파일: 탭 둘 · 활성 t2 · 이름 없는 탭 · 줌 · 프로파일 없는 창 · 숨긴 도구함을 그대로 읽고 알림이 없다")
    void readsV2Fixture() throws IOException {
        // given
        write(file(), fixture("v2.json"));

        // when
        LoadedWorkspace loaded = ok(repository().loadWorkspace());

        // then
        Workspace workspace = loaded.snapshot().orElseThrow().workspace();
        assertThat(workspace.tabs()).extracting(Tab::id).containsExactly(T1, T2);
        assertThat(workspace.active()).isEqualTo(1);
        assertThat(workspace.tabs().get(0).name()).contains(new TabName("개발"));
        assertThat(workspace.tab().name()).isEmpty();
        assertThat(workspace.tab().zoomed()).contains(P4);
        assertThat(workspace.spec(P4)).contains(PaneSpec.remembered(P4));
        assertThat(workspace.spec(P2)).contains(new PaneSpec(P2, Optional.of(UBUNTU_PROFILE), Optional.empty()));
        assertThat(workspace.spec(P1).flatMap(PaneSpec::dir)).contains(path(UBUNTU, "/home/me/한글 프로젝트"));
        assertThat(loaded.snapshot().orElseThrow().window().toolkit()).isEqualTo(new PanelState(false, 300));
        assertThat(loaded.notices()).isEmpty();
    }

    // ---- 왕복 ----

    @Test
    @DisplayName("saveWorkspace 뒤 새 저장소로 읽으면 같은 스냅샷이고, 한글 경로 · 탭 이름은 이스케이프 없이 쓴다")
    void roundTripThroughNewRepository() throws IOException {
        // given
        JsonStateRepository first = repository();
        ok(first.loadWorkspace());
        WorkspaceSnapshot saved = snapshot("한글 프로젝트", WindowState.DEFAULT);
        Tab named = new Tab(
                T1, Optional.of(new TabName("배포 🚀")), saved.workspace().tab().root(), P2, Optional.of(P2));
        WorkspaceSnapshot snapshot = new WorkspaceSnapshot(
                Workspace.restore(List.of(named), 0, saved.workspace().specs()), WindowState.DEFAULT);

        // when
        ok(first.saveWorkspace(snapshot));
        first.close();
        LoadedWorkspace loaded = ok(repository().loadWorkspace());

        // then
        assertThat(loaded.snapshot()).contains(snapshot);
        assertThat(loaded.notices()).isEmpty();
        String text = Files.readString(file(), StandardCharsets.UTF_8);
        assertThat(text)
                .contains("/home/me/한글 프로젝트")
                .contains("배포 🚀")
                .doesNotContain("\\u")
                .doesNotContain("\r");
        assertThat(onDisk().get("schemaVersion").longValue()).isEqualTo(2);
        assertThat(onDisk().get("workspace").has("nextPane")).isFalse();
    }

    @Test
    @DisplayName("이력 save 뒤 작업 공간 save, 그 반대 순서도 파일에는 두 부분 모두 마지막 값이 남는다")
    void twoPortsDoNotEraseEachOther() throws IOException {
        // given
        JsonStateRepository repository = repository();
        ok(repository.load());
        ok(repository.loadWorkspace());

        // when: 이력 → 작업 공간
        ok(repository.save(historyOf("C:\\first")));
        ok(repository.saveWorkspace(snapshot("a", WindowState.DEFAULT)));
        JsonNode afterWorkspace = onDisk();
        // when: 작업 공간 → 이력
        ok(repository.saveWorkspace(snapshot("b", WindowState.DEFAULT)));
        ok(repository.save(historyOf("C:\\second")));

        // then
        assertThat(afterWorkspace
                        .get("history")
                        .get("entries")
                        .get(0)
                        .get("path")
                        .stringValue())
                .isEqualTo("C:\\first");
        repository.close();
        JsonStateRepository reopened = repository();
        assertThat(ok(reopened.load()).history()).isEqualTo(historyOf("C:\\second"));
        assertThat(ok(reopened.loadWorkspace()).snapshot()).contains(snapshot("b", WindowState.DEFAULT));
    }

    @Test
    @DisplayName("window 안의 모르는 필드(toolkit.tab 등)는 창 상태를 바꿔 저장해도 남는다")
    void unknownWindowFieldsSurvive() throws IOException {
        // given
        write(
                file(),
                fixture("v2.json")
                        .replace("\"maximized\" : false,", "\"maximized\" : false, \"monitor\" : \"DISPLAY2\","));
        JsonStateRepository repository = repository();
        WorkspaceSnapshot loaded = ok(repository.loadWorkspace()).snapshot().orElseThrow();
        WindowState changed =
                new WindowState(Optional.empty(), true, new PanelState(false, 200), new PanelState(true, 320));

        // when
        ok(repository.saveWorkspace(new WorkspaceSnapshot(loaded.workspace(), changed)));

        // then
        JsonNode window = onDisk().get("window");
        assertThat(window.get("toolkit").get("tab").stringValue()).isEqualTo("SKILL");
        assertThat(window.get("monitor").stringValue()).isEqualTo("DISPLAY2");
        assertThat(window.get("toolkit").get("width").intValue()).isEqualTo(320);
        assertThat(window.get("maximized").booleanValue()).isTrue();
        assertThat(window.has("x")).isFalse();
        repository.close();
        assertThat(ok(repository().loadWorkspace()).snapshot().orElseThrow().window())
                .isEqualTo(changed);
    }

    @Test
    @DisplayName("작업 공간을 읽은 순서와 상관없이 문서는 한 번만 읽는다 — loadWorkspace 뒤 load 도 .bak 복구 알림을 받는다")
    void documentIsReadOncePerPair() throws IOException {
        // given
        write(file(), "{ \"schemaVersion\": 2, \"history\": ");
        write(dir.resolve("state.json.bak"), fixture("v2.json"));
        JsonStateRepository repository = repository();

        // when
        LoadedWorkspace workspace = ok(repository.loadWorkspace());
        LoadedHistory history = ok(repository.load());

        // then
        assertThat(workspace.snapshot()).isPresent();
        assertThat(history.notices()).singleElement().isInstanceOf(HistoryLoadNotice.RecoveredFromBackup.class);
        assertThat(corruptFiles()).hasSize(1);
    }

    // ---- 손상 ----

    @Test
    @DisplayName("JSON 문법 오류 + 정상 v2 .bak: 이력과 작업 공간이 모두 .bak 에서 복구되고 RecoveredFromBackup 이다")
    void syntaxErrorRecoversBothPartsFromBackup() throws IOException {
        // given
        write(file(), fixture("v1-corrupt.json"));
        write(dir.resolve("state.json.bak"), fixture("v2.json"));
        JsonStateRepository repository = repository();

        // when
        LoadedHistory history = ok(repository.load());
        LoadedWorkspace workspace = ok(repository.loadWorkspace());

        // then
        assertThat(history.notices()).singleElement().isInstanceOf(HistoryLoadNotice.RecoveredFromBackup.class);
        assertThat(history.history().entries()).hasSize(2);
        assertThat(workspace.snapshot().orElseThrow().workspace().tabs()).hasSize(2);
        assertThat(workspace.notices()).isEmpty();
        assertThat(Files.readString(file(), StandardCharsets.UTF_8)).isEqualTo(fixture("v2.json"));
    }

    @Test
    @DisplayName("창 하나의 profile 이 숫자면 그 창만 빠지고 형제로 축약된다 — PanesDropped(1), 이력은 그대로")
    void badPaneSpecIsDroppedAndCollapsed() throws IOException {
        // given: v2 예시의 p3 profile 을 숫자로
        write(file(), fixture("v2.json").replace("\"profile\" : \"powershell\"", "\"profile\" : 7"));
        JsonStateRepository repository = repository();

        // when
        LoadedHistory history = ok(repository.load());
        LoadedWorkspace loaded = ok(repository.loadWorkspace());

        // then
        Workspace workspace = loaded.snapshot().orElseThrow().workspace();
        assertThat(workspace.tabs().get(0).root())
                .isEqualTo(new LayoutNode.Split(Orientation.ROW, Ratio.HALF, leaf(P1), leaf(P2)));
        assertThat(workspace.specs()).containsOnlyKeys(P1, P2, P4);
        assertThat(loaded.notices()).containsExactly(new WorkspaceLoadNotice.PanesDropped(1));
        assertThat(history.history().entries()).hasSize(2);
        assertThat(history.notices()).isEmpty();
    }

    @Test
    @DisplayName("workspace 가 배열이면 이력은 살고 스냅샷은 비며 PartDiscarded(workspace), 파일은 옮기지 않는다")
    void workspaceArrayIsDiscardedOnly() throws IOException {
        // given
        String content = fixture("v1-history-only.json")
                .replace("\"schemaVersion\" : 1,", "\"schemaVersion\" : 2, \"workspace\" : [ 1 ],");
        write(file(), content);
        JsonStateRepository repository = repository();

        // when
        LoadedHistory history = ok(repository.load());
        LoadedWorkspace loaded = ok(repository.loadWorkspace());

        // then
        assertThat(history.history().entries()).hasSize(2);
        assertThat(history.notices()).isEmpty();
        assertThat(loaded.snapshot()).isEmpty();
        assertThat(loaded.notices())
                .singleElement()
                .isInstanceOfSatisfying(
                        WorkspaceLoadNotice.PartDiscarded.class,
                        n -> assertThat(n.part()).isEqualTo("workspace"));
        assertThat(corruptFiles()).isEmpty();
        assertThat(Files.readString(file(), StandardCharsets.UTF_8)).isEqualTo(content);
    }

    static Stream<Arguments> brokenWorkspaces() {
        String ok = pane("\"powershell\"", WIN_CWD);
        String bad = pane("7", "null");
        return Stream.of(
                Arguments.of("tabs 가 배열이 아님", "{ \"tabs\": {}, \"panes\": {} }", 0),
                Arguments.of("panes 가 객체가 아님", "{ \"tabs\": [], \"panes\": [] }", 0),
                Arguments.of("탭이 없음", "{ \"tabs\": [], \"panes\": {} }", 0),
                Arguments.of(
                        "모든 탭의 창이 틀림",
                        "{ \"tabs\": [ { \"id\": \"t1\", \"focused\": \"p1\", \"layout\": { \"pane\": \"p1\" } } ],"
                                + " \"panes\": { \"p1\": " + bad + " } }",
                        1),
                Arguments.of(
                        "레이아웃의 창이 명세에 없음",
                        "{ \"tabs\": [ { \"id\": \"t1\", \"layout\": { \"pane\": \"p9\" } } ], \"panes\": { \"p1\": "
                                + ok + " } }",
                        1));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("brokenWorkspaces")
    @DisplayName("남는 탭이 없으면 스냅샷은 비고 PartDiscarded(workspace) 를 알린다(뺀 창이 있으면 PanesDropped 도)")
    void nothingLeftDiscardsWorkspace(String caseName, String workspaceJson, int dropped) throws IOException {
        // given
        write(file(), v2With(workspaceJson));

        // when
        LoadedWorkspace loaded = ok(repository().loadWorkspace());

        // then
        assertThat(loaded.snapshot()).isEmpty();
        assertThat(loaded.notices())
                .last()
                .isInstanceOfSatisfying(
                        WorkspaceLoadNotice.PartDiscarded.class,
                        n -> assertThat(n.part()).isEqualTo("workspace"));
        if (dropped > 0) {
            assertThat(loaded.notices()).first().isEqualTo(new WorkspaceLoadNotice.PanesDropped(dropped));
        } else {
            assertThat(loaded.notices()).hasSize(1);
        }
    }

    @Test
    @DisplayName("탭의 창이 모두 틀리면 그 탭만 버리고, 활성 탭은 위치가 아니라 그 탭을 따라간다")
    void tabWithOnlyBadPanesIsDropped() throws IOException {
        // given: t1(p1 틀림) · t2(p2) · 활성 t2
        write(
                file(),
                v2With("{ \"activeTab\": 1, \"tabs\": ["
                        + " { \"id\": \"t1\", \"focused\": \"p1\", \"layout\": { \"pane\": \"p1\" } },"
                        + " { \"id\": \"t2\", \"focused\": \"p2\", \"layout\": { \"pane\": \"p2\" } } ],"
                        + " \"panes\": { \"p1\": " + pane("\"powershell\"", "\"C:\\\\x\"") + ", \"p2\": "
                        + pane("\"powershell\"", WIN_CWD) + " } }"));

        // when
        LoadedWorkspace loaded = ok(repository().loadWorkspace());

        // then
        Workspace workspace = loaded.snapshot().orElseThrow().workspace();
        assertThat(workspace.tabs()).extracting(Tab::id).containsExactly(T2);
        assertThat(workspace.active()).isZero();
        assertThat(loaded.notices()).containsExactly(new WorkspaceLoadNotice.PanesDropped(1));
    }

    @Test
    @DisplayName("포커스 창이 트리에 없으면 첫 창, 줌한 창이 없으면 줌 없음, 활성 탭이 범위 밖이면 0, 틀린 탭 이름은 자동 이름")
    void repairsFocusZoomActiveAndName() throws IOException {
        // given
        String ok = pane("\"powershell\"", WIN_CWD);
        write(
                file(),
                v2With("{ \"activeTab\": 5, \"tabs\": [ { \"id\": \"t1\", \"name\": \"   \", \"focused\": \"p9\","
                        + " \"zoomed\": \"p9\", \"layout\": { \"split\": \"COLUMN\", \"ratio\": 0.3,"
                        + " \"first\": { \"pane\": \"p1\" }, \"second\": { \"pane\": \"p2\" } } } ],"
                        + " \"panes\": { \"p1\": " + ok + ", \"p2\": " + ok + " } }"));

        // when
        LoadedWorkspace loaded = ok(repository().loadWorkspace());

        // then
        Workspace workspace = loaded.snapshot().orElseThrow().workspace();
        assertThat(workspace.active()).isZero();
        assertThat(workspace.tab().focused()).isEqualTo(P1);
        assertThat(workspace.tab().zoomed()).isEmpty();
        assertThat(workspace.tab().name()).isEmpty();
        assertThat(loaded.notices()).isEmpty();
    }

    static Stream<Arguments> brokenLayouts() {
        return Stream.of(
                Arguments.of(
                        "모르는 방향",
                        "{ \"split\": \"DIAGONAL\", \"ratio\": 0.5, \"first\": { \"pane\": \"p2\" }, \"second\": { \"pane\": \"p3\" } }"),
                Arguments.of(
                        "범위 밖 비율",
                        "{ \"split\": \"ROW\", \"ratio\": 0.95, \"first\": { \"pane\": \"p2\" }, \"second\": { \"pane\": \"p3\" } }"),
                Arguments.of(
                        "second 없음",
                        "{ \"split\": \"ROW\", \"ratio\": 0.5, \"first\": { \"pane\": \"p2\" }, \"third\": { \"pane\": \"p3\" } }"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("brokenLayouts")
    @DisplayName("틀린 분할 노드는 그 아래 창들과 함께 빠진다")
    void brokenSplitDropsItsSubtree(String caseName, String subtree) throws IOException {
        // given: [p1 | 틀린 노드(p2, p3)]
        String ok = pane("\"powershell\"", WIN_CWD);
        write(
                file(),
                v2With("{ \"tabs\": [ { \"id\": \"t1\", \"focused\": \"p1\", \"layout\": { \"split\": \"ROW\","
                        + " \"ratio\": 0.5, \"first\": { \"pane\": \"p1\" }, \"second\": " + subtree + " } } ],"
                        + " \"panes\": { \"p1\": " + ok + ", \"p2\": " + ok + ", \"p3\": " + ok + " } }"));

        // when
        LoadedWorkspace loaded = ok(repository().loadWorkspace());

        // then
        Workspace workspace = loaded.snapshot().orElseThrow().workspace();
        assertThat(workspace.tab().root()).isEqualTo(leaf(P1));
        assertThat(workspace.specs()).containsOnlyKeys(P1);
        assertThat(loaded.notices()).singleElement().isInstanceOf(WorkspaceLoadNotice.PanesDropped.class);
    }

    @Test
    @DisplayName("두 탭이 같은 창을 가리키면 뒤의 것을 빼고, 같은 탭 ID 의 뒤 탭은 버린다")
    void duplicatesAreDropped() throws IOException {
        // given
        String ok = pane("\"powershell\"", WIN_CWD);
        write(
                file(),
                v2With("{ \"tabs\": ["
                        + " { \"id\": \"t1\", \"layout\": { \"pane\": \"p1\" } },"
                        + " { \"id\": \"t2\", \"layout\": { \"split\": \"ROW\", \"ratio\": 0.5,"
                        + "   \"first\": { \"pane\": \"p1\" }, \"second\": { \"pane\": \"p2\" } } },"
                        + " { \"id\": \"t1\", \"layout\": { \"pane\": \"p3\" } } ],"
                        + " \"panes\": { \"p1\": " + ok + ", \"p2\": " + ok + ", \"p3\": " + ok + " } }"));

        // when
        LoadedWorkspace loaded = ok(repository().loadWorkspace());

        // then
        Workspace workspace = loaded.snapshot().orElseThrow().workspace();
        assertThat(workspace.tabs()).extracting(Tab::id).containsExactly(T1, T2);
        assertThat(workspace.tabs().get(1).root()).isEqualTo(leaf(P2));
        assertThat(loaded.notices()).containsExactly(new WorkspaceLoadNotice.PanesDropped(2));
    }

    static Stream<Arguments> brokenWindows() {
        return Stream.of(
                Arguments.of("객체가 아님", "[]"),
                Arguments.of("너비가 문자열", "{ \"x\": 0, \"y\": 0, \"width\": \"1600\", \"height\": 900 }"),
                Arguments.of("좌표 일부만", "{ \"x\": 0, \"width\": 1600, \"height\": 900 }"),
                Arguments.of("높이가 0", "{ \"x\": 0, \"y\": 0, \"width\": 1600, \"height\": 0 }"),
                Arguments.of("maximized 가 숫자", "{ \"maximized\": 1 }"),
                Arguments.of("사이드바가 문자열", "{ \"sidebar\": \"left\" }"),
                Arguments.of("도구함 폭이 음수", "{ \"toolkit\": { \"visible\": true, \"width\": -3 } }"),
                Arguments.of("사이드바 표시가 문자열", "{ \"sidebar\": { \"visible\": \"yes\", \"width\": 10 } }"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("brokenWindows")
    @DisplayName("window 의 값이 틀리면 창 상태만 기본값이고 PartDiscarded(window), 작업 공간은 그대로다")
    void brokenWindowFallsBackToDefault(String caseName, String windowJson) throws IOException {
        // given
        String workspace = "{ \"tabs\": [ { \"id\": \"t1\", \"layout\": { \"pane\": \"p1\" } } ], \"panes\": { \"p1\": "
                + pane("null", "null") + " } }";
        write(
                file(),
                "{ \"schemaVersion\": 2, \"history\": { \"entries\": [] }, \"workspace\": " + workspace
                        + ", \"window\": " + windowJson + " }");

        // when
        LoadedWorkspace loaded = ok(repository().loadWorkspace());

        // then
        WorkspaceSnapshot snapshot = loaded.snapshot().orElseThrow();
        assertThat(snapshot.window()).isEqualTo(WindowState.DEFAULT);
        assertThat(snapshot.workspace().tab().root()).isEqualTo(leaf(P1));
        assertThat(loaded.notices())
                .singleElement()
                .isInstanceOfSatisfying(
                        WorkspaceLoadNotice.PartDiscarded.class,
                        n -> assertThat(n.part()).isEqualTo("window"));
    }

    @Test
    @DisplayName("window 의 빠진 필드는 기본값으로 읽고 알리지 않는다")
    void missingWindowFieldsUseDefaults() throws IOException {
        // given
        write(
                file(),
                "{ \"schemaVersion\": 2, \"workspace\": { \"tabs\": [ { \"id\": \"t1\", \"layout\": { \"pane\": \"p1\" } } ],"
                        + " \"panes\": { \"p1\": {} } }, \"window\": { \"sidebar\": { \"width\": 300 } } }");

        // when
        LoadedWorkspace loaded = ok(repository().loadWorkspace());

        // then
        WorkspaceSnapshot snapshot = loaded.snapshot().orElseThrow();
        assertThat(snapshot.window())
                .isEqualTo(new WindowState(
                        Optional.empty(), false, new PanelState(true, 300), WindowState.DEFAULT.toolkit()));
        assertThat(snapshot.workspace().spec(P1)).contains(PaneSpec.remembered(P1));
        assertThat(loaded.notices()).isEmpty();
    }

    @Test
    @DisplayName("작업 공간 없이 window 만 있으면 스냅샷은 비지만, 이력을 저장해도 window 는 남는다")
    void windowWithoutWorkspaceSurvivesHistorySave() throws IOException {
        // given
        write(
                file(),
                "{ \"schemaVersion\": 2, \"history\": { \"entries\": [] }, \"window\": { \"maximized\": true } }");
        JsonStateRepository repository = repository();

        // when
        LoadedWorkspace loaded = ok(repository.loadWorkspace());
        ok(repository.load());
        ok(repository.save(historyOf("C:\\x")));

        // then
        assertThat(loaded.snapshot()).isEmpty();
        assertThat(onDisk().get("window")).isEqualTo(JSON.readTree("{ \"maximized\": true }"));
        assertThat(onDisk().has("workspace")).isFalse();
    }

    // ---- 읽기 전용 · 거부 ----

    @Test
    @DisplayName("schemaVersion 3 은 load 뒤 saveWorkspace 가 ReadOnly 이고 파일은 그대로다")
    void newerSchemaIsReadOnly() throws IOException {
        // given
        write(file(), fixture("v3-newer.json"));
        byte[] before = Files.readAllBytes(file());
        JsonStateRepository repository = repository();

        // when
        LoadedWorkspace loaded = ok(repository.loadWorkspace());
        Result<WorkspaceSnapshot, HistoryStoreError> saved =
                repository.saveWorkspace(snapshot("x", WindowState.DEFAULT));

        // then
        assertThat(loaded.access()).isEqualTo(StoreAccess.READ_ONLY);
        assertThat(saved.error())
                .contains(new HistoryStoreError.ReadOnly(HistoryStoreError.ReadOnlyReason.NEWER_SCHEMA));
        assertThat(Files.readAllBytes(file())).isEqualTo(before);
        assertThat(dir.resolve("state.json.bak")).doesNotExist();
    }

    @Test
    @DisplayName("load 전의 saveWorkspace 는 ReadOnly 이고 파일을 만들지 않는다")
    void saveBeforeLoadTouchesNothing() {
        // given
        JsonStateRepository repository = repository();

        // when
        Result<WorkspaceSnapshot, HistoryStoreError> saved =
                repository.saveWorkspace(snapshot("x", WindowState.DEFAULT));

        // then
        assertThat(saved.error()).contains(new HistoryStoreError.ReadOnly(HistoryStoreError.ReadOnlyReason.NOT_LOADED));
        assertThat(file()).doesNotExist();
    }

    @Test
    @DisplayName("닫은 뒤의 saveWorkspace 는 ReadOnly(CLOSED) 다")
    void saveAfterCloseIsReadOnly() {
        // given
        JsonStateRepository repository = repository();
        ok(repository.loadWorkspace());
        repository.close();

        // when
        Result<WorkspaceSnapshot, HistoryStoreError> saved =
                repository.saveWorkspace(snapshot("x", WindowState.DEFAULT));

        // then
        assertThat(saved.error()).contains(new HistoryStoreError.ReadOnly(HistoryStoreError.ReadOnlyReason.CLOSED));
    }

    @Test
    @DisplayName("저장 위치를 읽을 수 없으면 loadWorkspace 는 Io 오류다")
    void unreadableFileIsIoError() throws IOException {
        // given
        Files.createDirectories(file());

        // when
        Result<LoadedWorkspace, HistoryStoreError> loaded = repository().loadWorkspace();

        // then
        assertThat(loaded.error()).containsInstanceOf(HistoryStoreError.Io.class);
    }

    @Test
    @DisplayName("saveWorkspace 의 쓰기가 실패하면 Io 이고, 이후 이력 save 는 마지막으로 저장된 작업 공간을 쓴다")
    void failedWorkspaceSaveIsNotRemembered() throws IOException {
        // given
        JsonStateRepository repository = repository();
        ok(repository.loadWorkspace());
        ok(repository.saveWorkspace(snapshot("a", WindowState.DEFAULT)));
        // 임시 파일 자리를 비어 있지 않은 폴더로 막아 쓰기를 실패시킨다
        Path blocker = Files.createDirectories(dir.resolve("state.json.tmp"));
        Files.writeString(blocker.resolve("keep"), "x");

        // when
        Result<WorkspaceSnapshot, HistoryStoreError> failed =
                repository.saveWorkspace(snapshot("b", WindowState.DEFAULT));
        Files.delete(blocker.resolve("keep"));
        Files.delete(blocker);
        ok(repository.save(historyOf("C:\\x")));

        // then
        assertThat(failed.error()).containsInstanceOf(HistoryStoreError.Io.class);
        repository.close();
        assertThat(ok(repository().loadWorkspace()).snapshot()).contains(snapshot("a", WindowState.DEFAULT));
    }

    @Test
    @DisplayName("저장한 문서는 SDD 9.2 v2 모양이다 — 탭 name · zoomed · layout, 창 profile · lastCwd {env, path}")
    void writesV2Shape() throws IOException {
        // given
        JsonStateRepository repository = repository();
        ok(repository.loadWorkspace());
        WindowState window = new WindowState(
                Optional.of(new Bounds(1, 2, 300, 400)), false, new PanelState(true, 236), new PanelState(false, 264));

        // when
        ok(repository.saveWorkspace(snapshot("proj", window)));

        // then
        JsonNode root = onDisk();
        ObjectNode expectedWorkspace = (ObjectNode) JSON.readTree("""
                { "activeTab": 0,
                  "tabs": [ { "id": "t1", "name": null, "focused": "p1", "zoomed": null,
                              "layout": { "split": "ROW", "ratio": 0.5,
                                          "first": { "pane": "p1" }, "second": { "pane": "p2" } } } ],
                  "panes": { "p1": { "profile": "wsl:Ubuntu-24.04",
                                     "lastCwd": { "env": { "kind": "wsl", "distro": "Ubuntu-24.04" }, "path": "/home/me/proj" } },
                             "p2": { "profile": null, "lastCwd": null } } }
                """);
        assertThat(root.get("workspace")).isEqualTo(expectedWorkspace);
        assertThat(root.get("window")).isEqualTo(JSON.readTree("""
                        { "x": 1, "y": 2, "width": 300, "height": 400, "maximized": false,
                          "sidebar": { "visible": true, "width": 236 }, "toolkit": { "visible": false, "width": 264 } }
                        """));
        assertThat(root.get("history").get("entries")).isEmpty();
    }
}
