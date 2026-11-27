package tanager.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.LayoutManager;
import java.util.Objects;
import javax.swing.JComponent;
import javax.swing.JPanel;
import tanager.ui.motion.Animator;
import tanager.ui.motion.Motion;

/**
 * 사이드바가 놓이는 자리(Ctrl+Shift+E, #152). 보이기 · 숨기기는 사이드바를 왼쪽으로 밀어 넣고 빼는 전환이다.
 *
 * <p>자리 너비는 전환 중에 바꾸지 않는다 — 바꾸면 터미널 창들이 프레임마다 커지고 셸 크기도 프레임마다 바뀐다(경계선 드래그처럼 셸 크기는 한
 * 번만 바꾼다). 보일 때는 자리를 먼저 펴고 밀어 넣고, 숨길 때는 밀어 낸 뒤 자리를 접는다. EDT 에서만 쓴다.
 */
final class SidebarSlot {
    private final JPanel slot = new JPanel(new SlideLayout());
    private final JComponent sidebar;
    private final Animator slide;
    private double shownFraction = 1;
    private boolean shown = true;

    SidebarSlot(JComponent sidebar, Motion motion) {
        this.sidebar = Objects.requireNonNull(sidebar, "sidebar");
        this.slide = new Animator(motion, shownFraction, this::reveal);
        slot.setOpaque(false);
        slot.add(sidebar);
    }

    /** 메인 창에 놓을 컴포넌트. */
    JComponent component() {
        return slot;
    }

    /** 보이는 쪽으로 가고 있거나 보이는가. */
    boolean shown() {
        return shown;
    }

    void toggle() {
        if (shown) {
            hide();
        } else {
            show();
        }
    }

    void show() {
        shown = true;
        if (!slot.isVisible()) {
            slot.setVisible(true);
            revalidateWindow();
        }
        slide.animateTo(1);
    }

    void hide() {
        shown = false;
        slide.animateTo(0, () -> {
            slot.setVisible(false);
            revalidateWindow();
        });
    }

    /** 사이드바가 보이는 비율(0..1)을 정하고 다시 놓는다. 자리 크기는 그대로다. */
    void reveal(double fraction) {
        shownFraction = fraction;
        slot.doLayout();
        slot.repaint();
    }

    private void revalidateWindow() {
        Container parent = slot.getParent();
        if (parent != null) {
            parent.revalidate();
            parent.repaint();
        } else {
            slot.revalidate();
        }
    }

    /** 선호 크기는 사이드바 그대로, 사이드바는 보이는 비율만큼 오른쪽 끝을 드러낸다. */
    private final class SlideLayout implements LayoutManager {
        @Override
        public void addLayoutComponent(String name, Component comp) {}

        @Override
        public void removeLayoutComponent(Component comp) {}

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            return sidebar.getPreferredSize();
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return sidebar.getMinimumSize();
        }

        @Override
        public void layoutContainer(Container parent) {
            int width = parent.getWidth();
            int hidden = width - (int) Math.round(width * shownFraction);
            sidebar.setBounds(-hidden, 0, width, parent.getHeight());
        }
    }
}
