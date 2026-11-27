package tanager.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.in.AppEvent;
import tanager.application.port.in.OpenChoices;
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
        PaneSpec spec = ok(panes.open(A, Optional.empty(), Optional.empty()));

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
        ok(panes.open(A, Optional.empty(), Optional.empty()));

        // then
        assertThat(((LayoutNode.Split) workspace().tab().root()).orientation()).isEqualTo(Orientation.COLUMN);
    }

    @Test
    @DisplayName("기본 프로파일이 다른 환경이면 그 환경의 탐지 순서 첫 셸(powershell)로 연다")
    void defaultFromOtherEnvironment() {
        // given
        configure(Optional.of("wsl:A"), OpenPlacement.RIGHT);

        // when
        PaneSpec spec = ok(panes.open(A, Optional.empty(), Optional.empty()));

        // then
        assertThat(spec.profile()).contains(POWERSHELL.id());
    }

    @Test
    @DisplayName("설정을 읽지 못해도 기본값(RIGHT · 기억한 셸 없음)으로 연다")
    void unreadableConfigUsesDefaults() {
        // given
        config.willFailLoad(new tanager.application.port.out.ConfigError.Unreadable("권한"));

        // when
        PaneSpec spec = ok(panes.open(A, Optional.empty(), Optional.empty()));

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
        OpenError error = err(panes.open(z, Optional.empty(), Optional.empty()));

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
        PaneSpec spec = ok(panes.open(mnt, Optional.of(POWERSHELL.id()), Optional.empty()));

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
        assertThat(err(panes.open(home, Optional.of(POWERSHELL.id()), Optional.empty())))
                .isEqualTo(new OpenError.EnvironmentMismatch(POWERSHELL.id(), home));
        assertThat(err(panes.open(A, Optional.of(new ProfileId("gone")), Optional.empty())))
                .isEqualTo(new OpenError.UnknownProfile(new ProfileId("gone")));
        assertThat(workspace()).isEqualTo(Workspace.initial());
    }

    @Test
    @DisplayName("지정한 프로파일이 경로 환경과 같으면 그것으로 연다")
    void explicitProfile() {
        assertThat(ok(panes.open(A, Optional.of(CMD.id()), Optional.empty())).profile())
                .contains(CMD.id());
    }

    @Test
    @DisplayName("p2 를 닫으면 p1 하나가 되고 포커스 p1, p2 의 세션은 LiveSessions 에서 빠진다")
    void closeRemovesPaneAndSession() {
        // given
        ok(panes.open(A, Optional.empty(), Optional.empty()));
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
        ok(panes.open(A, Optional.empty(), Optional.empty()));
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

    @Test
    @DisplayName("메뉴에서 고른 자리(아래)는 설정(오른쪽)보다 우선한다(#91)")
    void explicitPlacementWins() {
        // given
        configure(Optional.empty(), OpenPlacement.RIGHT);

        // when
        ok(panes.open(A, Optional.empty(), Optional.of(OpenPlacement.DOWN)));

        // then
        assertThat(((LayoutNode.Split) workspace().tab().root()).orientation()).isEqualTo(Orientation.COLUMN);
    }

    @Test
    @DisplayName("환경 노드의 새 창은 그 환경의 기본 셸을 그 셸의 시작 경로(→ 홈)에서 연다, 셸이 없으면 NoProfileForEnvironment(#91)")
    void openHome() {
        // given
        configure(Optional.of("cmd"), OpenPlacement.RIGHT);

        // when
        PaneSpec spec = ok(panes.openHome(WIN, Optional.empty()));

        // then
        assertThat(spec).isEqualTo(new PaneSpec(P2, Optional.of(CMD.id()), Optional.empty()));
        assertThat(err(panes.openHome(new ShellEnvironment.Wsl("Z"), Optional.empty())))
                .isEqualTo(new OpenError.NoProfileForEnvironment(new ShellEnvironment.Wsl("Z")));
    }

    @Test
    @DisplayName("열기 메뉴 후보: Windows 폴더는 기본 셸 + 같은 환경의 다른 셸 · 경로를 바꿀 수 있는 WSL(탐지 순서)(#91)")
    void choicesForWindowsFolder() {
        // given
        configure(Optional.of("cmd"), OpenPlacement.RIGHT);

        // when
        OpenChoices choices = panes.choices(A);

        // then
        assertThat(choices.primary()).contains(CMD);
        assertThat(choices.others()).containsExactly(WSL_A, POWERSHELL);
    }

    @Test
    @DisplayName("열기 메뉴 후보: WSL 홈은 Windows 셸로 열 수 없고, 지운 배포판은 기본 셸도 없다(#91)")
    void choicesWithoutTranslation() {
        // given
        WorkspacePath home = WorkspacePath.of(new ShellEnvironment.Wsl("A"), "/home/me")
                .value()
                .orElseThrow();
        WorkspacePath gone = WorkspacePath.of(new ShellEnvironment.Wsl("Z"), "/home/me")
                .value()
                .orElseThrow();

        // then
        assertThat(panes.choices(home)).isEqualTo(new OpenChoices(Optional.of(WSL_A), List.of()));
        assertThat(panes.choices(gone)).isEqualTo(new OpenChoices(Optional.empty(), List.of()));
    }

    // pane 에 profile 셸이 dir 에서 떠 있다(Running)
    private void launched(PaneId pane, ShellProfile profile, WorkingDir dir) {
        TerminalSession running = TerminalSession.start(profile, dir)
                .value()
                .orElseThrow()
                .launched(42)
                .value()
                .orElseThrow();
        state.submit(current -> new Transition<>(
                        current.withSessions(current.sessions().launched(pane, running)), List.of(), true))
                .toCompletableFuture()
                .join();
    }

    @Test
    @DisplayName("같은 경로에 새 창: 그 세션 창 옆에 같은 프로파일 · 같은 경로로 열고 새 창에 포커스, LayoutChanged(#92)")
    void openSameBesideSessionPane() {
        // given — p1(cmd, C:\a) · p2 가 있고 포커스는 p2
        configure(Optional.empty(), OpenPlacement.RIGHT);
        ok(panes.open(A, Optional.empty(), Optional.empty()));
        launched(P1, CMD, new WorkingDir.At(A));
        events.clear();

        // when
        PaneSpec spec = ok(panes.openSame(P1, Optional.of(OpenPlacement.DOWN)));

        // then
        PaneId p3 = new PaneId("p3");
        assertThat(spec).isEqualTo(new PaneSpec(p3, Optional.of(CMD.id()), Optional.of(A)));
        assertThat(workspace().tab().root())
                .isEqualTo(new LayoutNode.Split(
                        Orientation.ROW,
                        Ratio.HALF,
                        new LayoutNode.Split(
                                Orientation.COLUMN, Ratio.HALF, new LayoutNode.Leaf(P1), new LayoutNode.Leaf(p3)),
                        new LayoutNode.Leaf(P2)));
        assertThat(workspace().tab().focused()).isEqualTo(p3);
        assertThat(events).containsExactly(new AppEvent.LayoutChanged(workspace()));
    }

    @Test
    @DisplayName("같은 경로에 새 창: 경로를 모르는 세션(홈)은 그 프로파일의 시작 경로(→ 홈), 자리가 비면 설정 자리(#92)")
    void openSameWithoutCwdUsesProfileHome() {
        // given
        configure(Optional.empty(), OpenPlacement.DOWN);
        launched(P1, WSL_A, new WorkingDir.Home());

        // when
        PaneSpec spec = ok(panes.openSame(P1, Optional.empty()));

        // then
        assertThat(spec).isEqualTo(new PaneSpec(P2, Optional.of(WSL_A.id()), Optional.empty()));
        assertThat(((LayoutNode.Split) workspace().tab().root()).orientation()).isEqualTo(Orientation.COLUMN);
    }

    @Test
    @DisplayName("같은 경로에 새 창: 세션이 없는 창이면 UnknownPane 이고 레이아웃 · 이벤트는 그대로다(#92)")
    void openSameUnknownPane() {
        // given
        configure(Optional.empty(), OpenPlacement.RIGHT);

        // when
        OpenError error = err(panes.openSame(P1, Optional.empty()));

        // then
        assertThat(error).isEqualTo(new OpenError.UnknownPane(P1));
        assertThat(workspace()).isEqualTo(Workspace.initial());
        assertThat(events).isEmpty();
    }
}
