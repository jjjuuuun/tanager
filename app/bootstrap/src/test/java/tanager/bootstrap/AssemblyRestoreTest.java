package tanager.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tanager.application.port.in.AppEvent;
import tanager.application.port.out.DirectUiDispatcher;
import tanager.application.port.out.FakePlatformModule;
import tanager.application.port.out.HistoryLoadNotice;
import tanager.application.port.out.ScriptedPtyLauncher;
import tanager.application.port.out.WorkspaceLoadNotice;
import tanager.application.state.HistoryStore;
import tanager.application.state.Transition;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.layout.Orientation;
import tanager.domain.session.IntegrationMode;
import tanager.domain.session.LaunchTemplate;
import tanager.domain.session.ProfileId;
import tanager.domain.session.ShellKind;
import tanager.domain.session.ShellProfile;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.TabId;
import tanager.domain.workspace.Workspace;

/** 시작 때 state.json 을 창보다 먼저 읽어 작업 공간을 복원하고, 바뀌면 1초 안에 저장한다(FR-RES-01 · 02, #179). */
class AssemblyRestoreTest {
    private static final ShellProfile CMD = new ShellProfile(
            new ProfileId("cmd"),
            "cmd",
            ShellKind.CMD,
            new ShellEnvironment.Windows(),
            new LaunchTemplate("cmd.exe", List.of()),
            IntegrationMode.NONE);
    private static final PaneId P1 = new PaneId("p1");
    private static final PaneId P2 = new PaneId("p2");
    private static final PaneId P3 = new PaneId("p3");

    // 탭 2개(t1: p1 | p2, t2: p3) · 활성 t1 · 포커스 p2
    private static final String SAVED = """
            {"schemaVersion":%d,"history":{"entries":[]},
             "workspace":{"activeTab":0,"tabs":[
               {"id":"t1","name":null,"focused":"p2","zoomed":null,
                "layout":{"split":"ROW","ratio":0.5,"first":{"pane":"p1"},"second":{"pane":"p2"}}},
               {"id":"t2","name":null,"focused":"p3","zoomed":null,"layout":{"pane":"p3"}}],
              "panes":{
               "p1":{"profile":"cmd","lastCwd":{"env":{"kind":"windows"},"path":"C:\\\\work\\\\a"}},
               "p2":{"profile":"cmd","lastCwd":{"env":{"kind":"windows"},"path":"C:\\\\work\\\\b"}},
               "p3":{"profile":"cmd","lastCwd":{"env":{"kind":"windows"},"path":"C:\\\\gone"}}}}}
            """;

    @TempDir
    Path appData;

    private final List<Assembly> assemblies = new ArrayList<>();

    @AfterEach
    void shutdownAssemblies() {
        assemblies.forEach(Assembly::shutdown);
    }

    private Assembly assemble(String json) throws Exception {
        if (json != null) {
            Files.writeString(appData.resolve("state.json"), json, StandardCharsets.UTF_8);
        }
        FakePlatformModule platform = new FakePlatformModule(List.of(CMD)).withAppData(appData);
        Assembly assembly = Assembly.assemble(platform, new ScriptedPtyLauncher(), new DirectUiDispatcher());
        assemblies.add(assembly);
        return assembly;
    }

    private static WorkspacePath win(String raw) {
        return WorkspacePath.of(new ShellEnvironment.Windows(), raw).value().orElseThrow();
    }

    private static void split(Assembly assembly) {
        assembly.state().submit(current -> {
            Workspace next = current.workspace()
                    .split(Orientation.COLUMN, Optional.empty(), Optional.empty())
                    .workspace();
            return new Transition<>(current.withWorkspace(next), List.of(new AppEvent.LayoutChanged(next)), true);
        });
    }

    @Test
    @DisplayName("조립이 끝나면(창을 만들기 전) 처음 작업 공간이 저장된 탭 · 분할 · 포커스 · 창별 명세 그대로다")
    void initialWorkspaceIsRestoredBeforeWindow() throws Exception {
        // when
        Assembly assembly = assemble(SAVED.formatted(2));

        // then 불러오기 결과를 기다리지 않고 바로 읽어도 복원돼 있다 — p1 기본 셸 하나로 시작하지 않는다
        Workspace workspace = assembly.state().snapshot().workspace();
        assertThat(workspace.tabs()).extracting(tab -> tab.id()).containsExactly(new TabId("t1"), new TabId("t2"));
        assertThat(workspace.tab().id()).isEqualTo(new TabId("t1"));
        assertThat(workspace.tab().root().panes()).containsExactly(P1, P2);
        assertThat(workspace.tab().focused()).isEqualTo(P2);
        assertThat(workspace.spec(P1).orElseThrow().profile()).contains(CMD.id());
        assertThat(workspace.spec(P1).orElseThrow().dir()).contains(win("C:\\work\\a"));
        assertThat(workspace.spec(P3).orElseThrow().dir()).contains(win("C:\\gone"));
        assertThat(workspace.nextPane()).isEqualTo(4);
    }

    @Test
    @DisplayName("state.json 이 없으면 창 p1 하나로 시작한다")
    void withoutStateFileStartsWithInitial() throws Exception {
        // when
        Assembly assembly = assemble(null);

        // then
        assertThat(assembly.state().snapshot().workspace()).isEqualTo(Workspace.initial());
    }

    @Test
    @DisplayName("불러오기 결과 · 알림은 startStore 를 부른 뒤(UI 가 구독한 뒤)에 나간다")
    void storeResultsArePublishedOnStartStore() throws Exception {
        // given v1 파일은 v2 로 바꿔 읽는다
        Assembly assembly = assemble(SAVED.formatted(1).replace("\"name\":null,", ""));
        List<AppEvent> events = new CopyOnWriteArrayList<>();
        assembly.state().subscribe(events::add);
        assertThat(assembly.state().snapshot().store()).isInstanceOf(HistoryStore.Loading.class);

        // when
        assembly.startStore();

        // then
        await().atMost(Duration.ofSeconds(5))
                .until(() -> assembly.state().snapshot().store() instanceof HistoryStore.Writable);
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(events)
                        .contains(new AppEvent.StoreNotice(
                                new AppEvent.StoreProblem.WorkspaceLoaded(new WorkspaceLoadNotice.Migrated(1, 2)))));
    }

    @Test
    @DisplayName("창을 나누면 1초쯤 뒤 state.json 의 작업 공간에 새 창이 저장된다")
    void splitIsSavedWithinASecond() throws Exception {
        // given
        Assembly assembly = assemble(SAVED.formatted(2));
        assembly.startStore();
        await().atMost(Duration.ofSeconds(5))
                .until(() -> assembly.state().snapshot().store() instanceof HistoryStore.Writable);

        // when
        split(assembly);

        // then 새 창 번호는 p4 다
        await().atMost(Duration.ofSeconds(3))
                .until(() -> Files.readString(appData.resolve("state.json"), StandardCharsets.UTF_8)
                        .contains("\"p4\""));
    }

    @Test
    @DisplayName("저장된 작업 공간을 불러오고 아무것도 바꾸지 않으면 종료해도 state.json 이 그대로다")
    void unchangedRestoreLeavesFileUntouched() throws Exception {
        // given
        Assembly assembly = assemble(SAVED.formatted(2));
        assembly.startStore();
        await().atMost(Duration.ofSeconds(5))
                .until(() -> assembly.state().snapshot().store() instanceof HistoryStore.Writable);
        byte[] before = Files.readAllBytes(appData.resolve("state.json"));

        // when
        assembly.shutdown();

        // then
        assertThat(Files.readAllBytes(appData.resolve("state.json"))).isEqualTo(before);
    }

    @Test
    @DisplayName("종료 때 미저장 작업 공간 변경을 바로 저장한다")
    void shutdownFlushesWorkspace() throws Exception {
        // given
        Assembly assembly = assemble(SAVED.formatted(2));
        assembly.startStore();
        await().atMost(Duration.ofSeconds(5))
                .until(() -> assembly.state().snapshot().store() instanceof HistoryStore.Writable);
        split(assembly);
        await().atMost(Duration.ofSeconds(5))
                .until(() -> assembly.state().snapshot().workspace().specs().size() == 4);

        // when
        assembly.shutdown();

        // then
        assertThat(Files.readString(appData.resolve("state.json"), StandardCharsets.UTF_8))
                .contains("\"p4\"");
    }

    @Test
    @DisplayName("새 버전이 쓴 파일(READ_ONLY)이면 창을 나눠도 쓰지 않고, 알림은 불러올 때 한 번뿐이다")
    void readOnlyStoreNeverWritesWorkspace() throws Exception {
        // given
        Assembly assembly = assemble(SAVED.formatted(3));
        byte[] before = Files.readAllBytes(appData.resolve("state.json"));
        List<AppEvent> events = new CopyOnWriteArrayList<>();
        assembly.state().subscribe(events::add);
        assembly.startStore();
        await().atMost(Duration.ofSeconds(5))
                .until(() -> assembly.state().snapshot().store() instanceof HistoryStore.ReadOnly);

        int panes = assembly.state().snapshot().workspace().specs().size();

        // when
        split(assembly);
        await().atMost(Duration.ofSeconds(5))
                .until(() -> assembly.state().snapshot().workspace().specs().size() == panes + 1);
        assembly.shutdown();

        // then
        assertThat(Files.readAllBytes(appData.resolve("state.json"))).isEqualTo(before);
        assertThat(events.stream().filter(AppEvent.StoreNotice.class::isInstance))
                .containsExactly(new AppEvent.StoreNotice(
                        new AppEvent.StoreProblem.Loaded(new HistoryLoadNotice.NewerSchema(3))));
    }
}
