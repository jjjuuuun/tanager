package tanager.domain.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 앱 창의 위치 · 크기 · 패널 상태(#178, FR-RES-03). */
class WindowStateTest {

    @Test
    @DisplayName("기본값은 OS 가 정한 자리 · 최대화 아님 · 사이드바 236px · 도구함 264px 이고 둘 다 보인다")
    void defaultFollowsMockupD() {
        // when
        WindowState state = WindowState.DEFAULT;

        // then
        assertThat(state.bounds()).isEmpty();
        assertThat(state.maximized()).isFalse();
        assertThat(state.sidebar()).isEqualTo(new PanelState(true, 236));
        assertThat(state.toolkit()).isEqualTo(new PanelState(true, 264));
    }

    @Test
    @DisplayName("창 위치는 음수일 수 있지만(왼쪽 모니터) 너비 · 높이는 1 이상이다")
    void boundsNeedPositiveSize() {
        // when
        Bounds left = new Bounds(-1600, -20, 1, 1);

        // then
        assertThat(left.x()).isEqualTo(-1600);
        assertThatIllegalArgumentException().isThrownBy(() -> new Bounds(0, 0, 0, 10));
        assertThatIllegalArgumentException().isThrownBy(() -> new Bounds(0, 0, 10, 0));
    }

    @Test
    @DisplayName("패널 폭은 0 이상이다")
    void panelWidthIsNotNegative() {
        // when
        PanelState hidden = new PanelState(false, 0);

        // then
        assertThat(hidden.width()).isZero();
        assertThatIllegalArgumentException().isThrownBy(() -> new PanelState(true, -1));
    }

    @Test
    @DisplayName("창 상태는 null 을 받지 않는다")
    void windowStateRejectsNull() {
        // given
        PanelState panel = new PanelState(true, 200);

        // then
        assertThat(new WindowState(Optional.of(new Bounds(1, 2, 3, 4)), true, panel, panel).maximized())
                .isTrue();
        assertThatNullPointerException().isThrownBy(() -> new WindowState(null, false, panel, panel));
        assertThatNullPointerException().isThrownBy(() -> new WindowState(Optional.empty(), false, null, panel));
        assertThatNullPointerException().isThrownBy(() -> new WindowState(Optional.empty(), false, panel, null));
    }
}
