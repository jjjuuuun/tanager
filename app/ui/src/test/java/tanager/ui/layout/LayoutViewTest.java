package tanager.ui.layout;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Rectangle;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.JPanel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.domain.layout.LayoutGeometry;
import tanager.domain.layout.LayoutNode;
import tanager.domain.layout.Orientation;
import tanager.domain.layout.Rect;
import tanager.domain.shared.PaneId;

class LayoutViewTest {
    private static final int GAP = 6;
    private static final PaneId P1 = new PaneId("p1");
    private static final PaneId P2 = new PaneId("p2");
    private static final LayoutNode TWO =
            new LayoutNode.Leaf(P1).split(P1, Orientation.ROW, P2).value().orElseThrow();

    @Test
    @DisplayName("창 영역은 배치가 쓰는 사각형과 같다 — 방향 포커스 · 교환이 화면과 같은 이웃을 찾는다")
    void areaMatchesLayout() {
        // given
        LayoutView view = new LayoutView(TWO, GAP);
        JComponent first = new JPanel();
        JComponent second = new JPanel();
        view.component().setSize(800, 600);
        view.show(TWO, Map.of(P1, first, P2, second));
        // 화면에 놓지 않은 컨테이너는 validate 가 배치하지 않는다(peer 없음) — 배치를 직접 돌린다
        view.component().doLayout();

        // when
        Rect area = view.area();

        // then
        assertThat(area).isEqualTo(new Rect(0, 0, 800, 600));
        Rect expected = LayoutGeometry.rects(TWO, area, GAP).get(P2);
        assertThat(second.getBounds())
                .isEqualTo(new Rectangle(expected.x(), expected.y(), expected.width(), expected.height()));
    }
}
