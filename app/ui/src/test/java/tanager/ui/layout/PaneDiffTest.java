package tanager.ui.layout;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.layout.Orientation;
import tanager.domain.session.ProfileId;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.TabId;
import tanager.domain.workspace.Workspace;

/** 작업 공간이 바뀌었을 때 새로 만들 창 · 없앨 창(#89). */
class PaneDiffTest {
    private static final PaneId P1 = new PaneId("p1");
    private static final PaneId P2 = new PaneId("p2");
    private static final WorkspacePath A =
            WorkspacePath.of(new ShellEnvironment.Windows(), "C:\\a").value().orElseThrow();

    private static Workspace two() {
        return Workspace.initial()
                .openBeside(Orientation.ROW, new ProfileId("cmd"), Optional.of(A))
                .workspace();
    }

    @Test
    @DisplayName("{p1} → {p1, p2} 면 추가 [p2] · 제거 [], 반대면 제거 [p2]")
    void addedAndRemoved() {
        assertThat(PaneDiff.between(Workspace.initial(), two())).isEqualTo(new PaneDiff(List.of(P2), List.of()));
        assertThat(PaneDiff.between(two(), Workspace.initial())).isEqualTo(new PaneDiff(List.of(), List.of(P2)));
    }

    @Test
    @DisplayName("포커스만 바뀌면 추가도 제거도 없다")
    void focusOnly() {
        Workspace focused = two().focus(P1).value().orElseThrow();
        assertThat(PaneDiff.between(two(), focused).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("이름이 같아도 명세가 바뀐 창은 없애고 다시 만든다(닫은 번호는 다시 쓰지 않으므로 생기지 않지만 지킨다)")
    void sameIdDifferentSpec() {
        Workspace b = Workspace.initial()
                .openBeside(Orientation.ROW, new ProfileId("powershell"), Optional.of(A))
                .workspace();
        assertThat(PaneDiff.between(two(), b)).isEqualTo(new PaneDiff(List.of(P2), List.of(P2)));
    }

    @Test
    @DisplayName("다른 탭의 창이 닫혀도 제거에 든다 — 활성 탭 밖의 셸도 닫는다(#177)")
    void removedFromInactiveTab() {
        // given — [t1(p1), t2(p2 | p3)], 활성 t1
        Workspace tabs = Workspace.initial()
                .newTab(Optional.empty(), Optional.empty())
                .workspace()
                .split(Orientation.ROW, Optional.empty(), Optional.empty())
                .workspace()
                .select(new TabId("t1"))
                .value()
                .orElseThrow();

        // when
        Workspace closed = tabs.close(P2).value().orElseThrow();
        Workspace tabClosed = tabs.closeTab(new TabId("t2")).value().orElseThrow();

        // then
        assertThat(PaneDiff.between(tabs, closed)).isEqualTo(new PaneDiff(List.of(), List.of(P2)));
        assertThat(PaneDiff.between(tabs, tabClosed)).isEqualTo(new PaneDiff(List.of(), List.of(P2, new PaneId("p3"))));
    }

    @Test
    @DisplayName("탭을 고르거나 창을 다른 탭 · 새 탭으로 옮기면 명세가 같아 다시 만들 창이 없다(#177)")
    void tabMovesKeepPanes() {
        // given — [t1(p1 | p2)], 활성 t1
        Workspace one = two();

        // when
        Workspace movedOut = one.movePaneToNewTab(P2).value().orElseThrow();
        Workspace selected = movedOut.select(new TabId("t1")).value().orElseThrow();
        Workspace movedBack =
                selected.movePaneToTab(P2, new TabId("t1")).value().orElseThrow();

        // then
        assertThat(PaneDiff.between(one, movedOut).isEmpty()).isTrue();
        assertThat(PaneDiff.between(movedOut, selected).isEmpty()).isTrue();
        assertThat(PaneDiff.between(selected, movedBack).isEmpty()).isTrue();
    }
}
