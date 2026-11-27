package tanager.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.time.Duration;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.domain.workspace.PanelState;
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
    @DisplayName("접힌 상태로 만들면 처음부터 자리가 보이지 않는다 — 전환 없이 숨긴 채 뜬다")
    void startsHidden() throws Exception {
        onEdt(() -> {
            // given
            JPanel sidebar = sidebar();

            // when
            SidebarSlot slot = new SidebarSlot(sidebar, OFF, new PanelState(false, 300));

            // then
            assertThat(slot.shown()).isFalse();
            assertThat(slot.component().isVisible()).isFalse();
            assertThat(slot.state()).isEqualTo(new PanelState(false, 300));
        });
    }

    @Test
    @DisplayName("접힌 채 만든 뒤 show 하면 저장된 폭으로 제자리에 보인다")
    void startsHiddenThenShows() throws Exception {
        onEdt(() -> {
            // given
            JPanel sidebar = sidebar();
            SidebarSlot slot = new SidebarSlot(sidebar, OFF, new PanelState(false, 300));

            // when
            slot.show();
            slot.component().setSize(300, 100);
            slot.component().doLayout();

            // then
            assertThat(slot.state()).isEqualTo(new PanelState(true, 300));
            assertThat(slot.component().isVisible()).isTrue();
            assertThat(sidebar.getBounds()).isEqualTo(new Rectangle(0, 0, 300, 100));
        });
    }

    @Test
    @DisplayName("저장된 폭이 사이드바의 선호 너비가 된다")
    void savedWidthBecomesPreferredWidth() throws Exception {
        onEdt(() -> {
            // when
            SidebarSlot slot = new SidebarSlot(sidebar(), OFF, new PanelState(true, 236));

            // then
            assertThat(slot.component().getPreferredSize().width).isEqualTo(236);
            assertThat(slot.state()).isEqualTo(new PanelState(true, 236));
        });
    }

    @Test
    @DisplayName("손으로 고친 터무니없는 폭(0 · 5000)은 쓸 수 있는 범위로 줄인다")
    void absurdWidthIsClamped() throws Exception {
        onEdt(() -> {
            // when
            SidebarSlot narrow = new SidebarSlot(sidebar(), OFF, new PanelState(true, 0));
            SidebarSlot wide = new SidebarSlot(sidebar(), OFF, new PanelState(true, 5000));

            // then
            assertThat(narrow.state().width()).isEqualTo(SidebarSlot.MIN_WIDTH);
            assertThat(wide.state().width()).isEqualTo(SidebarSlot.MAX_WIDTH);
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
