package tanager.ui;

import java.awt.Frame;
import java.awt.Rectangle;
import java.util.Objects;
import java.util.Optional;
import tanager.domain.workspace.Bounds;
import tanager.domain.workspace.PanelState;
import tanager.domain.workspace.WindowState;

/**
 * 프레임의 지금 모습을 저장할 창 상태로 옮긴다(FR-RES-03, #180). 순수 — 프레임을 읽는 일은 {@link MainWindow} 가 한다.
 *
 * <p>{@code bounds} 는 보통(최대화 · 최소화 아님) 상태의 자리만 쓴다. 최대화 중에는 최대화 직전의 보통 크기를 그대로 둬, 다음 실행에서 최대화를
 * 풀면 그 크기로 돌아간다. 최소화 중의 자리(Windows 는 -32000)는 쓰지 않는다.
 */
final class WindowCapture {
    private WindowCapture() {}

    /**
     * @param frame 프레임의 지금 자리({@code getBounds()})
     * @param extendedState 프레임의 확장 상태({@code getExtendedState()}: {@link Frame#NORMAL} · {@link Frame#MAXIMIZED_BOTH} ·
     *     {@link Frame#ICONIFIED} 의 조합)
     */
    static WindowState framed(WindowState current, Rectangle frame, int extendedState) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(frame, "frame");
        if ((extendedState & Frame.ICONIFIED) != 0) {
            return current;
        }
        boolean maximized = (extendedState & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH;
        Optional<Bounds> bounds = current.bounds();
        // 가로 · 세로 한쪽만 최대화해도 보통 자리가 아니다
        if ((extendedState & Frame.MAXIMIZED_BOTH) == 0) {
            if (frame.width < 1 || frame.height < 1) {
                return current;
            }
            bounds = Optional.of(new Bounds(frame.x, frame.y, frame.width, frame.height));
        }
        return new WindowState(bounds, maximized, current.sidebar(), current.toolkit());
    }

    /** 사이드바를 보이거나 접었다. 폭은 기억한다. */
    static WindowState sidebar(WindowState current, boolean visible) {
        Objects.requireNonNull(current, "current");
        return new WindowState(
                current.bounds(),
                current.maximized(),
                new PanelState(visible, current.sidebar().width()),
                current.toolkit());
    }
}
