package tanager.domain.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.domain.layout.LayoutNode;
import tanager.domain.layout.Orientation;
import tanager.domain.layout.Ratio;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.TabId;

/** 저장한 탭 · 명세에서 작업 공간 되살리기 — 다음 번호는 저장하지 않고 계산한다(#178). */
class WorkspaceRestoreTest {
    private static final PaneId P2 = new PaneId("p2");
    private static final PaneId P7 = new PaneId("p7");
    private static final PaneId NAMED = new PaneId("main");

    private static Map<PaneId, PaneSpec> specs(PaneId... panes) {
        Map<PaneId, PaneSpec> specs = new HashMap<>();
        for (PaneId pane : panes) {
            specs.put(pane, PaneSpec.remembered(pane));
        }
        return specs;
    }

    @Test
    @DisplayName("다음 창 · 탭 번호는 쓰인 가장 큰 번호 다음이다")
    void nextNumbersFollowLargestUsed() {
        // given
        Tab first = new Tab(
                new TabId("t3"),
                new LayoutNode.Split(Orientation.ROW, Ratio.HALF, new LayoutNode.Leaf(P7), new LayoutNode.Leaf(P2)),
                P2);
        Tab second = new Tab(new TabId("t1"), new LayoutNode.Leaf(NAMED), NAMED);

        // when
        Workspace workspace = Workspace.restore(List.of(first, second), 1, specs(P7, P2, NAMED));

        // then
        assertThat(workspace.nextPane()).isEqualTo(8);
        assertThat(workspace.nextTab()).isEqualTo(4);
        assertThat(workspace.active()).isEqualTo(1);
        assertThat(workspace.tabs()).containsExactly(first, second);
    }

    @Test
    @DisplayName("번호 꼴 ID 가 없으면 다음 번호는 1 이다")
    void withoutNumberedIdsStartsAtOne() {
        // given
        Tab tab = new Tab(new TabId("work"), new LayoutNode.Leaf(NAMED), NAMED);

        // when
        Workspace workspace = Workspace.restore(List.of(tab), 0, specs(NAMED));

        // then
        assertThat(workspace.nextPane()).isEqualTo(1);
        assertThat(workspace.nextTab()).isEqualTo(1);
    }

    @Test
    @DisplayName("다음 번호를 int 로 나타낼 수 없는 ID 는 불변식 위반이다")
    void tooLargeNumberIsRejected() {
        // given
        PaneId huge = new PaneId("p" + Integer.MAX_VALUE);
        Tab tab = new Tab(new TabId("t1"), new LayoutNode.Leaf(huge), huge);

        // then
        assertThatIllegalArgumentException().isThrownBy(() -> Workspace.restore(List.of(tab), 0, specs(huge)));
    }
}
