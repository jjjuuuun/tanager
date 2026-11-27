package tanager.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.time.Duration;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.ui.motion.Motion;

/** 사이드바 자리(#152). 화면 없이 배치만 본다. 전환 진행은 AnimatorTest 가 본다. */
class SidebarSlotTest {
    private static final int WIDTH = 240;
    private static final Motion OFF = new Motion(Duration.ofMillis(180), false);

    private static JPanel sidebar() {
        JPanel sidebar = new JPanel();
        sidebar.setPreferredSize(new Dimension(WIDTH, 0));
        return sidebar;
    }

    private static void onEdt(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
    }

    @Test
    @DisplayName("다 보이면 사이드바를 자리 전체에 놓고 선호 너비는 사이드바 너비다")
    void fullyShown() throws Exception {
        onEdt(() -> {
            // given
            JPanel sidebar = sidebar();
            SidebarSlot slot = new SidebarSlot(sidebar, OFF);

            // when
            slot.component().setSize(WIDTH, 100);
            slot.component().doLayout();

            // then
            assertThat(slot.shown()).isTrue();
            assertThat(slot.component().getPreferredSize().width).isEqualTo(WIDTH);
            assertThat(sidebar.getBounds()).isEqualTo(new Rectangle(0, 0, WIDTH, 100));
        });
    }

    @Test
    @DisplayName("전환 중에는 사이드바가 왼쪽으로 밀려 나간다 — 자리 크기는 그대로라 터미널 크기가 바뀌지 않는다")
    void slidesWithoutResizingSlot() throws Exception {
        onEdt(() -> {
            // given
            JPanel sidebar = sidebar();
            SidebarSlot slot = new SidebarSlot(sidebar, OFF);
            slot.component().setSize(WIDTH, 100);

            // when
            slot.reveal(0.25);

            // then
            assertThat(sidebar.getBounds()).isEqualTo(new Rectangle(-180, 0, WIDTH, 100));
            assertThat(slot.component().getPreferredSize().width).isEqualTo(WIDTH);
        });
    }

    @Test
    @DisplayName("애니메이션이 꺼져 있으면 hide 는 바로 자리를 숨긴다")
    void hideInstantly() throws Exception {
        onEdt(() -> {
            // given
            SidebarSlot slot = new SidebarSlot(sidebar(), OFF);

            // when
            slot.hide();

            // then
            assertThat(slot.shown()).isFalse();
            assertThat(slot.component().isVisible()).isFalse();
        });
    }

    @Test
    @DisplayName("숨긴 뒤 show 하면 자리가 다시 보이고 사이드바가 제자리에 있다")
    void showAgain() throws Exception {
        onEdt(() -> {
            // given
            JPanel sidebar = sidebar();
            SidebarSlot slot = new SidebarSlot(sidebar, OFF);
            slot.hide();

            // when
            slot.show();
            slot.component().setSize(WIDTH, 100);
            slot.component().doLayout();

            // then
            assertThat(slot.shown()).isTrue();
            assertThat(slot.component().isVisible()).isTrue();
            assertThat(sidebar.getX()).isZero();
        });
    }

    @Test
    @DisplayName("toggle 은 보이면 숨기고 숨겼으면 보인다")
    void toggle() throws Exception {
        onEdt(() -> {
            // given
            SidebarSlot slot = new SidebarSlot(sidebar(), OFF);

            // when / then
            slot.toggle();
            assertThat(slot.shown()).isFalse();
            slot.toggle();
            assertThat(slot.shown()).isTrue();
        });
    }
}
