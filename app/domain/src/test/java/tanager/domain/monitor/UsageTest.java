package tanager.domain.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 사용량 값(SDD §5.6, #187). */
class UsageTest {

    @Test
    @DisplayName("ratio 는 사용량 ÷ 전체다")
    void ratio() {
        // given
        Usage usage = new Usage(3, 4);

        // then
        assertThat(usage.ratio()).isCloseTo(0.75, within(1e-9));
        assertThat(new Usage(0, 10).ratio()).isZero();
        assertThat(new Usage(10, 10).ratio()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("used 가 total 보다 크면 예외다")
    void usedAboveTotal() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Usage(11, 10));
    }

    @Test
    @DisplayName("total 이 0 이하이거나 used 가 음수면 예외다")
    void nonPositiveTotalOrNegativeUsed() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Usage(0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new Usage(-1, 10));
    }

    @Test
    @DisplayName("ratio 가 임계값 이상이면 WARN, 아래면 NORMAL 이다")
    void levelAgainstThreshold() {
        assertThat(new Usage(85, 100).level(0.85)).isEqualTo(UsageLevel.WARN);
        assertThat(new Usage(84, 100).level(0.85)).isEqualTo(UsageLevel.NORMAL);
    }
}
