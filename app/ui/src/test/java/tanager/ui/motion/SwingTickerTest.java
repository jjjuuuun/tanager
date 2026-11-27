package tanager.ui.motion;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.DisplayMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SwingTickerTest {

    @Test
    @DisplayName("프레임 간격은 화면 주사율을 따른다")
    void intervalFollowsRefreshRate() {
        // when / then
        assertThat(SwingTicker.intervalMillis(60)).isEqualTo(16);
        assertThat(SwingTicker.intervalMillis(144)).isEqualTo(6);
    }

    @Test
    @DisplayName("주사율을 모르면 60Hz 로 본다")
    void unknownRefreshRateFallsBack() {
        // when / then
        assertThat(SwingTicker.intervalMillis(DisplayMode.REFRESH_RATE_UNKNOWN)).isEqualTo(16);
        assertThat(SwingTicker.intervalMillis(-3)).isEqualTo(16);
    }

    @Test
    @DisplayName("아주 높은 주사율에서도 간격은 1ms 이상이다")
    void intervalAtLeastOneMilli() {
        // when / then
        assertThat(SwingTicker.intervalMillis(5000)).isEqualTo(1);
    }
}
