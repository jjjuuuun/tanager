package tanager.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.in.RestoreWorkspaceUseCase;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.layout.LayoutNode;
import tanager.domain.layout.Orientation;
import tanager.domain.layout.Ratio;
import tanager.domain.session.ProfileId;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.TabId;
import tanager.domain.workspace.Bounds;
import tanager.domain.workspace.PaneSpec;
import tanager.domain.workspace.PanelState;
import tanager.domain.workspace.Tab;
import tanager.domain.workspace.WindowState;
import tanager.domain.workspace.Workspace;
import tanager.domain.workspace.WorkspaceSnapshot;

/** 시작 때 저장된 스냅샷을 이번 실행의 작업 공간으로(FR-RES-02, #179). */
class WorkspaceRestorerTest {
    private static final PaneId P1 = new PaneId("p1");
    private static final PaneId P2 = new PaneId("p2");
    private static final PaneId P3 = new PaneId("p3");
    private static final ShellEnvironment UBUNTU = new ShellEnvironment.Wsl("Ubuntu");

    private final RestoreWorkspaceUseCase restorer = new WorkspaceRestorer();

    private static PaneSpec spec(PaneId pane, String raw) {
        return new PaneSpec(
                pane,
                Optional.of(new ProfileId("wsl:Ubuntu")),
                Optional.of(WorkspacePath.of(UBUNTU, raw).value().orElseThrow()));
    }

    @Test
    @DisplayName("저장된 작업 공간이 없으면 창 p1 하나로 시작한다")
    void withoutSnapshotStartsWithInitial() {
        // when
        Workspace planned = restorer.plan(Optional.empty());

        // then
        assertThat(planned).isEqualTo(Workspace.initial());
    }

    @Test
    @DisplayName("저장된 탭 2개(t1: p1 | p2, t2: p3) · 활성 t1 · 포커스 p2 를 그대로 복원한다")
    void restoresTabsSplitsFocusAndSpecs() {
        // given
        Tab t1 = new Tab(
                new TabId("t1"),
                new LayoutNode.Split(Orientation.ROW, Ratio.HALF, new LayoutNode.Leaf(P1), new LayoutNode.Leaf(P2)),
                P2);
        Tab t2 = new Tab(new TabId("t2"), new LayoutNode.Leaf(P3), P3);
        Workspace saved = Workspace.restore(
                List.of(t1, t2), 0, Map.of(P1, spec(P1, "/a"), P2, spec(P2, "/b"), P3, spec(P3, "/home/me/gone")));

        // when
        Workspace planned = restorer.plan(Optional.of(new WorkspaceSnapshot(saved, WindowState.DEFAULT)));

        // then
        assertThat(planned).isEqualTo(saved);
        assertThat(planned.tab().focused()).isEqualTo(P2);
        assertThat(planned.spec(P3)).contains(spec(P3, "/home/me/gone"));
    }

    @Test
    @DisplayName("저장된 창 상태가 없으면 기본 창 상태로 시작한다")
    void withoutSnapshotWindowIsDefault() {
        // when
        WindowState window = restorer.window(Optional.empty());

        // then
        assertThat(window).isEqualTo(WindowState.DEFAULT);
    }

    @Test
    @DisplayName("저장된 창 상태(자리 · 최대화 · 사이드바)를 그대로 쓴다")
    void savedWindowIsKept() {
        // given
        WindowState saved = new WindowState(
                Optional.of(new Bounds(-1600, 120, 1400, 900)),
                true,
                new PanelState(false, 300),
                new PanelState(true, 264));

        // when
        WindowState window = restorer.window(Optional.of(new WorkspaceSnapshot(Workspace.initial(), saved)));

        // then
        assertThat(window).isEqualTo(saved);
    }
}
