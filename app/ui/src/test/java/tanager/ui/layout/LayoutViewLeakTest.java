package tanager.ui.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.JPanel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.domain.layout.LayoutNode;
import tanager.domain.layout.Orientation;
import tanager.domain.shared.PaneId;

/** 창을 되풀이해 붙였다 떼어도 배치가 옛 창을 붙잡지 않는다(#223). 화면 없이 돈다. */
class LayoutViewLeakTest {
    private static final PaneId P1 = new PaneId("p1");
    private static final int ROUNDS = 50;

    @Test
    @DisplayName("분할 → 닫기를 50번 그려도 자식 수는 기준선이고 뗀 창 · 손잡이는 수거된다")
    void splitAndCloseRepeatedly() {
        // given
        LayoutNode single = new LayoutNode.Leaf(P1);
        JComponent first = new JPanel();
        LayoutView view = new LayoutView(single, 6, () -> new PaneMinimum(0, 0), (path, ratio) -> {});
        view.component().setSize(800, 600);
        view.show(single, Map.of(P1, first));
        int children = view.component().getComponentCount();
        List<WeakReference<JComponent>> removed = new ArrayList<>();

        // when
        for (int round = 0; round < ROUNDS; round++) {
            PaneId added = new PaneId("p" + (round + 2));
            JPanel pane = new JPanel();
            JPanel handle = new JPanel();
            pane.add(handle);
            view.addDragHandle(handle);
            Map<PaneId, JComponent> two = new LinkedHashMap<>();
            two.put(P1, first);
            two.put(added, pane);
            view.show(single.split(P1, Orientation.ROW, added).value().orElseThrow(), two);
            view.show(single, Map.of(P1, first));
            removed.add(new WeakReference<>(pane));
            removed.add(new WeakReference<>(handle));
        }

        // then
        assertThat(view.component().getComponentCount()).isEqualTo(children);
        assertThat(first.getParent()).isSameAs(view.component());
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    System.gc();
                    return removed.stream().allMatch(reference -> reference.get() == null);
                });
    }
}
