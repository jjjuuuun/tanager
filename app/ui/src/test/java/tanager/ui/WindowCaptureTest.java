package tanager.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Frame;
import java.awt.Rectangle;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.domain.workspace.Bounds;
import tanager.domain.workspace.PanelState;
import tanager.domain.workspace.WindowState;

/** 프레임의 자리 · 확장 상태 · 사이드바를 저장할 창 상태로 옮긴다(FR-RES-03, #180). 화면 없이 값만 본다. */
class WindowCaptureTest {
    private static final Bounds NORMAL = new Bounds(100, 80, 1200, 800);
    private static final WindowState PLACED =
            new WindowState(Optional.of(NORMAL), false, new PanelState(true, 236), new PanelState(false, 300));

    @Test
    @DisplayName("보통 상태에서 옮기거나 크기를 바꾸면 그 자리 · 크기를 쓴다")
    void normalFrameRecordsBounds() {
        // when
        WindowState next = WindowCapture.framed(PLACED, new Rectangle(300, 200, 1000, 700), Frame.NORMAL);

        // then
        assertThat(next.bounds()).contains(new Bounds(300, 200, 1000, 700));
        assertThat(next.maximized()).isFalse();
        assertThat(next.sidebar()).isEqualTo(PLACED.sidebar());
        assertThat(next.toolkit()).isEqualTo(PLACED.toolkit());
    }

    @Test
    @DisplayName("처음 여는 창(자리 없음)도 보통 상태의 자리를 쓴다")
    void defaultFrameRecordsBounds() {
        // when
        WindowState next = WindowCapture.framed(WindowState.DEFAULT, new Rectangle(360, 140, 1200, 800), Frame.NORMAL);

        // then
        assertThat(next.bounds()).contains(new Bounds(360, 140, 1200, 800));
    }

    @Test
    @DisplayName("최대화하면 최대화만 기록하고 bounds 는 최대화 전 보통 크기 그대로다")
    void maximizedKeepsNormalBounds() {
        // when
        WindowState next = WindowCapture.framed(PLACED, new Rectangle(-8, -8, 1936, 1056), Frame.MAXIMIZED_BOTH);

        // then
        assertThat(next.maximized()).isTrue();
        assertThat(next.bounds()).contains(NORMAL);
    }

    @Test
    @DisplayName("최대화를 풀면 최대화가 꺼지고 돌아온 보통 자리를 쓴다")
    void restoreFromMaximized() {
        // given
        WindowState maximized = WindowCapture.framed(PLACED, new Rectangle(-8, -8, 1936, 1056), Frame.MAXIMIZED_BOTH);

        // when
        WindowState next = WindowCapture.framed(maximized, new Rectangle(100, 80, 1200, 800), Frame.NORMAL);

        // then
        assertThat(next.maximized()).isFalse();
        assertThat(next.bounds()).contains(NORMAL);
    }

    @Test
    @DisplayName("최소화 중의 자리(-32000 등)는 기록하지 않는다 — 최대화였는지도 그대로다")
    void iconifiedIsIgnored() {
        // given
        WindowState maximized = WindowCapture.framed(PLACED, new Rectangle(-8, -8, 1936, 1056), Frame.MAXIMIZED_BOTH);

        // when
        WindowState minimizedNormal =
                WindowCapture.framed(PLACED, new Rectangle(-32000, -32000, 160, 28), Frame.ICONIFIED);
        WindowState minimizedMaximized = WindowCapture.framed(
                maximized, new Rectangle(-32000, -32000, 160, 28), Frame.ICONIFIED | Frame.MAXIMIZED_BOTH);

        // then
        assertThat(minimizedNormal).isEqualTo(PLACED);
        assertThat(minimizedMaximized).isEqualTo(maximized);
    }

    @Test
    @DisplayName("크기가 0 인 자리(아직 배치 전)는 기록하지 않는다")
    void emptyRectangleIsIgnored() {
        // when
        WindowState next = WindowCapture.framed(PLACED, new Rectangle(0, 0, 0, 0), Frame.NORMAL);

        // then
        assertThat(next).isEqualTo(PLACED);
    }

    @Test
    @DisplayName("사이드바를 접으면 보이지 않음만 바꾸고 폭은 기억한다")
    void sidebarHiddenKeepsWidth() {
        // when
        WindowState hidden = WindowCapture.sidebar(PLACED, false);
        WindowState shownAgain = WindowCapture.sidebar(hidden, true);

        // then
        assertThat(hidden.sidebar()).isEqualTo(new PanelState(false, 236));
        assertThat(hidden.bounds()).isEqualTo(PLACED.bounds());
        assertThat(hidden.toolkit()).isEqualTo(PLACED.toolkit());
        assertThat(shownAgain).isEqualTo(PLACED);
    }
}
