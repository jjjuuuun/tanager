package tanager.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.in.AppEvent;
import tanager.application.port.in.OpenError;
import tanager.application.port.out.AppConfig;
import tanager.application.port.out.InMemoryConfigRepository;
import tanager.application.port.out.LoadedConfig;
import tanager.application.port.out.OpenPlacement;
import tanager.application.state.AppState;
import tanager.application.state.StateActor;
import tanager.application.state.Transition;
import tanager.domain.history.HistoryPolicy;
import tanager.domain.history.PathTranslator;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkHistory;
import tanager.domain.history.WorkspacePath;
import tanager.domain.history.WslMount;
import tanager.domain.layout.LayoutError;
import tanager.domain.layout.LayoutNode;
import tanager.domain.layout.Orientation;
import tanager.domain.layout.Ratio;
import tanager.domain.session.IntegrationMode;
import tanager.domain.session.LaunchTemplate;
import tanager.domain.session.ProfileId;
import tanager.domain.session.ShellKind;
import tanager.domain.session.ShellProfile;
import tanager.domain.session.TerminalSession;
import tanager.domain.session.WorkingDir;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.Result;
import tanager.domain.workspace.PaneSpec;
import tanager.domain.workspace.Workspace;

/** 경로에서 새 창 열기 · 창 닫기 · 포커스(#88, FR-HIS-05). */
class PaneManagerTest {
    private static final ShellEnvironment WIN = new ShellEnvironment.Windows();
    private static final ShellProfile WSL_A = profile("wsl:A", ShellKind.BASH, new ShellEnvironment.Wsl("A"));
    private static final ShellProfile POWERSHELL = profile("powershell", ShellKind.WINDOWS_POWERSHELL, WIN);
    private static final ShellProfile CMD = profile("cmd", ShellKind.CMD, WIN);
    private static final PaneId P1 = new PaneId("p1");
    private static final PaneId P2 = new PaneId("p2");
    private static final WorkspacePath A =
            WorkspacePath.of(WIN, "C:\\a").value().orElseThrow();

    private final InMemoryConfigRepository config = new InMemoryConfigRepository();
    private final StateActor state =
            new StateActor(AppState.initial(WorkHistory.empty(HistoryPolicy.DEFAULT)), Runnable::run);
    private final List<AppEvent> events = new ArrayList<>();
    private final PaneManager panes = new PaneManager(
            () -> List.of(WSL_A, POWERSHELL, CMD),
            config,
            (from, to) -> PathTranslator.translate(from, to, WslMount.DEFAULT),
            state);

    PaneManagerTest() {
        state.subscribe(events::add);
    }

    private static ShellProfile profile(String id, ShellKind kind, ShellEnvironment env) {
        return new ShellProfile(
                new ProfileId(id), id, kind, env, new LaunchTemplate(id + ".exe", List.of()), IntegrationMode.NONE);
    }

    private void configure(Optional<String> defaultProfile, OpenPlacement placement) {
        AppConfig stored = new AppConfig(defaultProfile.map(ProfileId::new), Map.of(), placement);
        config.willLoad(Result.ok(new LoadedConfig(stored, List.of())));
    }

    private static <T, E> T ok(java.util.concurrent.CompletionStage<Result<T, E>> stage) {
        Result<T, E> result = stage.toCompletableFuture().join();
        return result.value().orElseThrow(() -> new AssertionError("오류: " + result.error()));
    }

    private static <T, E> E err(java.util.concurrent.CompletionStage<Result<T, E>> stage) {
        return stage.toCompletableFuture().join().error().orElseThrow();
    }

    private Workspace workspace() {
        return state.snapshot().workspace();
    }

    @Test
    @DisplayName("오른쪽에 열면 포커스 창 옆에 p2 가 생기고 포커스, 명세는 그 환경의 기본 프로파일 · 그 경로, LayoutChanged")
    void openRight() {
        // given
        configure(Optional.of("cmd"), OpenPlacement.RIGHT);

        // when
        PaneSpec spec = ok(panes.open(A, Optional.empty()));

        // then
        assertThat(spec).isEqualTo(new PaneSpec(P2, Optional.of(CMD.id()), Optional.of(A)));
        assertThat(workspace().tab().root())
                .isEqualTo(new LayoutNode.Split(
                        Orientation.ROW, Ratio.HALF, new LayoutNode.Leaf(P1), new LayoutNode.Leaf(P2)));
        assertThat(workspace().tab().focused()).isEqualTo(P2);
        assertThat(events).containsExactly(new AppEvent.LayoutChanged(workspace()));
    }

    @Test
    @DisplayName("설정이 DOWN 이면 위아래(COLUMN)로 나눈다")
    void openDown() {
        // given
        configure(Optional.empty(), OpenPlacement.DOWN);

        // when
        ok(panes.open(A, Optional.empty()));

        // then
        assertThat(((LayoutNode.Split) workspace().tab().root()).orientation()).isEqualTo(Orientation.COLUMN);
    }

    @Test
    @DisplayName("기본 프로파일이 다른 환경이면 그 환경의 탐지 순서 첫 셸(powershell)로 연다")
    void defaultFromOtherEnvironment() {
        // given
        configure(Optional.of("wsl:A"), OpenPlacement.RIGHT);

        // when
        PaneSpec spec = ok(panes.open(A, Optional.empty()));

        // then
        assertThat(spec.profile()).contains(POWERSHELL.id());
    }

    @Test
    @DisplayName("설정을 읽지 못해도 기본값(RIGHT · 기억한 셸 없음)으로 연다")
    void unreadableConfigUsesDefaults() {
        // given
        config.willFailLoad(new tanager.application.port.out.ConfigError.Unreadable("권한"));

        // when
        PaneSpec spec = ok(panes.open(A, Optional.empty()));

        // then
        assertThat(spec.profile()).contains(POWERSHELL.id());
        assertThat(((LayoutNode.Split) workspace().tab().root()).orientation()).isEqualTo(Orientation.ROW);
    }

    @Test
    @DisplayName("경로 환경의 셸이 탐지 목록에 없으면 NoProfileForEnvironment 이고 레이아웃 · 이벤트는 그대로다")
    void noProfileForEnvironment() {
        // given
        WorkspacePath z = WorkspacePath.of(new ShellEnvironment.Wsl("Z"), "/home/me")
                .value()
                .orElseThrow();

        // when
        OpenError error = err(panes.open(z, Optional.empty()));

        // then
        assertThat(error).isEqualTo(new OpenError.NoProfileForEnvironment(new ShellEnvironment.Wsl("Z")));
        assertThat(workspace()).isEqualTo(Workspace.initial());
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("지정한 프로파일의 환경이 달라도 경로를 바꿀 수 있으면 바꾼 경로로 연다(#90)")
    void translatesPathForOtherEnvironment() {
        // given
        WorkspacePath mnt = WorkspacePath.of(new ShellEnvironment.Wsl("A"), "/mnt/c/work")
                .value()
                .orElseThrow();

        // when
        PaneSpec spec = ok(panes.open(mnt, Optional.of(POWERSHELL.id())));

        // then
        WorkspacePath windows = WorkspacePath.of(WIN, "C:\\work").value().orElseThrow();
        assertThat(spec).isEqualTo(new PaneSpec(P2, Optional.of(POWERSHELL.id()), Optional.of(windows)));
    }

    @Test
    @DisplayName("바꿀 수 없는 경로(WSL 홈)를 다른 환경 프로파일로 열면 EnvironmentMismatch, 모르는 프로파일이면 UnknownProfile")
    void profileErrors() {
        WorkspacePath home = WorkspacePath.of(new ShellEnvironment.Wsl("A"), "/home/me")
                .value()
                .orElseThrow();
        assertThat(err(panes.open(home, Optional.of(POWERSHELL.id()))))
                .isEqualTo(new OpenError.EnvironmentMismatch(POWERSHELL.id(), home));
        assertThat(err(panes.open(A, Optional.of(new ProfileId("gone")))))
                .isEqualTo(new OpenError.UnknownProfile(new ProfileId("gone")));
        assertThat(workspace()).isEqualTo(Workspace.initial());
    }

    @Test
    @DisplayName("지정한 프로파일이 경로 환경과 같으면 그것으로 연다")
    void explicitProfile() {
        assertThat(ok(panes.open(A, Optional.of(CMD.id()))).profile()).contains(CMD.id());
    }

    @Test
    @DisplayName("p2 를 닫으면 p1 하나가 되고 포커스 p1, p2 의 세션은 LiveSessions 에서 빠진다")
    void closeRemovesPaneAndSession() {
        // given
        ok(panes.open(A, Optional.empty()));
        TerminalSession running = TerminalSession.start(CMD, new WorkingDir.At(A))
                .value()
                .orElseThrow()
                .launched(42)
                .value()
                .orElseThrow();
        state.submit(current -> new Transition<>(
                        current.withSessions(current.sessions().launched(P2, running)), List.of(), true))
                .toCompletableFuture()
                .join();
        events.clear();

        // when
        Workspace closed = ok(panes.close(P2));

        // then
        assertThat(closed.tab().root()).isEqualTo(new LayoutNode.Leaf(P1));
        assertThat(closed.tab().focused()).isEqualTo(P1);
        assertThat(state.snapshot().sessions().byPane()).doesNotContainKey(P2);
        assertThat(events)
                .containsExactly(
                        new AppEvent.LayoutChanged(closed),
                        new AppEvent.SessionsChanged(state.snapshot().sessions()));
    }

    @Test
    @DisplayName("창 하나를 닫으면 LastPane 이고 LastPaneClosed 이벤트가 나간다")
    void closeLastPane() {
        // when
        LayoutError error = err(panes.close(P1));

        // then
        assertThat(error).isEqualTo(new LayoutError.LastPane(P1));
        assertThat(events).containsExactly(new AppEvent.LastPaneClosed());
    }

    @Test
    @DisplayName("포커스를 옮기면 LayoutChanged, 이미 포커스면 이벤트 없음, 없는 창이면 UnknownPane")
    void focus() {
        // given
        configure(Optional.empty(), OpenPlacement.RIGHT);
        ok(panes.open(A, Optional.empty()));
        events.clear();

        // when
        Workspace focused = ok(panes.focus(P1));
        ok(panes.focus(P1));

        // then
        assertThat(focused.tab().focused()).isEqualTo(P1);
        assertThat(events).containsExactly(new AppEvent.LayoutChanged(focused));
        assertThat(err(panes.focus(new PaneId("p9")))).isEqualTo(new LayoutError.UnknownPane(new PaneId("p9")));
    }

    @Test
    @DisplayName("앱 상태의 시작 작업 공간은 창 p1 하나다")
    void initialWorkspace() {
        assertThat(workspace()).isEqualTo(Workspace.initial());
    }
}
