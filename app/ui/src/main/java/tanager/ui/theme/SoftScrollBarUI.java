package tanager.ui.theme;

import com.formdev.flatlaf.ui.FlatScrollBarUI;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Rectangle;
import javax.swing.JComponent;
import javax.swing.UIManager;
import javax.swing.plaf.ComponentUI;

/**
 * 평소에는 얇고 마우스를 올리거나 끌 때만 굵어지는 스크롤바(SDD §8.5, #63). FlatLaf 3.8 에는 손잡이 굵기를 상태에 따라 바꾸는 키가
 * 없어, 손잡이를 그릴 때만 안쪽 여백을 바꾼다. 굵기는 테마 파일이 정한다 — 평소 {@code Tanager.scrollBar.idleThumbInsets}, 올렸을
 * 때 {@code ScrollBar.thumbInsets}.
 */
public final class SoftScrollBarUI extends FlatScrollBarUI {
    private static final String IDLE_INSETS = "Tanager.scrollBar.idleThumbInsets";

    private Insets idleThumbInsets = new Insets(0, 0, 0, 0);

    /** UIManager 가 {@code ScrollBarUI} 키로 부른다. */
    public static ComponentUI createUI(JComponent component) {
        return new SoftScrollBarUI();
    }

    @Override
    protected void installDefaults() {
        super.installDefaults();
        Insets idle = UIManager.getInsets(IDLE_INSETS);
        idleThumbInsets = idle != null ? idle : thumbInsets;
    }

    /** 손잡이 안쪽 여백: 올렸으면 굵게(작은 여백), 아니면 얇게. */
    Insets thumbInsets(boolean hovered) {
        return hovered ? thumbInsets : idleThumbInsets;
    }

    @Override
    protected void paintThumb(Graphics g, JComponent c, Rectangle thumbBounds) {
        Insets hoverInsets = thumbInsets;
        thumbInsets = thumbInsets(hoverTrack || hoverThumb || isDragging);
        try {
            super.paintThumb(g, c, thumbBounds);
        } finally {
            thumbInsets = hoverInsets;
        }
    }
}
