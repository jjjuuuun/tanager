package tanager.ui;

import java.awt.AlphaComposite;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Window;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.RootPaneContainer;
import tanager.ui.motion.Animator;
import tanager.ui.motion.Motion;

/**
 * 테마를 바꾸는 동안 열린 창마다 옛 화면을 찍어 맨 위에 덮고, 바꾼 뒤 테마의 전환 시간({@link Motion}) 동안 걷는다(#185). FlatLaf 의
 * {@code FlatAnimatedLafChange}(flatlaf-extras)와 같은 방식이다 — 그 하나 때문에 의존성을 늘리지 않으려고 직접 둔다. EDT 에서만 쓴다.
 */
final class SnapshotFade implements LiveTheme.Fade {
    // 드래그 미리보기보다도 위
    private static final int LAYER = JLayeredPane.DRAG_LAYER + 1;

    private final List<Cover> covers = new ArrayList<>();
    private Optional<Animator> fading = Optional.empty();

    @Override
    public void cover() {
        // 걷는 중에 또 바뀌면 이전 전환을 멈춘다 — 그 끝 콜백이 새로 덮은 것을 걷지 않게
        fading.ifPresent(Animator::cancel);
        fading = Optional.empty();
        removeAll();
        for (Window window : Window.getWindows()) {
            if (window.isShowing() && window instanceof RootPaneContainer container) {
                JLayeredPane layers = container.getLayeredPane();
                if (layers.getWidth() > 0 && layers.getHeight() > 0) {
                    Cover cover = new Cover(snapshot(layers));
                    cover.setBounds(0, 0, layers.getWidth(), layers.getHeight());
                    layers.add(cover, Integer.valueOf(LAYER));
                    covers.add(cover);
                }
            }
        }
    }

    @Override
    public void reveal() {
        if (covers.isEmpty()) {
            return;
        }
        // 전환 시간은 새 테마의 토큰에서 읽는다. reveal 은 움직임이 켜져 있을 때만 불린다
        Animator animator = new Animator(Motion.fromTheme(true), 1, this::opacity);
        fading = Optional.of(animator);
        animator.animateTo(0, this::removeAll);
    }

    private void opacity(double value) {
        for (Cover cover : covers) {
            cover.opacity = (float) value;
            cover.repaint();
        }
    }

    private void removeAll() {
        for (Cover cover : covers) {
            if (cover.getParent() instanceof JLayeredPane layers) {
                layers.remove(cover);
                layers.repaint();
            }
        }
        covers.clear();
    }

    // 화면 배율(HiDPI)만큼 크게 찍어 덮는 동안 흐려 보이지 않게 한다
    private static BufferedImage snapshot(JLayeredPane layers) {
        GraphicsConfiguration config = layers.getGraphicsConfiguration();
        AffineTransform scale = config == null ? new AffineTransform() : config.getDefaultTransform();
        int width = (int) Math.ceil(layers.getWidth() * scale.getScaleX());
        int height = (int) Math.ceil(layers.getHeight() * scale.getScaleY());
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.scale(scale.getScaleX(), scale.getScaleY());
            layers.paint(g);
        } finally {
            g.dispose();
        }
        return image;
    }

    /** 찍은 화면을 {@code opacity} 만큼 비치게 그린다. 마우스는 받지 않는다(리스너가 없다). */
    private static final class Cover extends JComponent {
        private final BufferedImage image;
        private float opacity = 1f;

        Cover(BufferedImage image) {
            this.image = image;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setComposite(AlphaComposite.SrcOver.derive(Math.clamp(opacity, 0f, 1f)));
                g2.drawImage(image, 0, 0, getWidth(), getHeight(), null);
            } finally {
                g2.dispose();
            }
        }
    }
}
