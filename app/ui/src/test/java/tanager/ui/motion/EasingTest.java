package tanager.ui.motion;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EasingTest {

    @Test
    @DisplayName("ease-out 은 t=0 에서 0, t=1 에서 1 이다")
    void endpoints() {
        // when / then
        assertThat(Easing.easeOut(0)).isEqualTo(0.0);
        assertThat(Easing.easeOut(1)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("ease-out 은 앞쪽이 빠르다 — 절반 시간에 절반보다 많이 와 있다")
    void fastStart() {
        // when
        double half = Easing.easeOut(0.5);

        // then
        assertThat(half).isGreaterThan(0.5).isLessThan(1.0);
    }

    @Test
    @DisplayName("범위 밖 진행률은 0..1 로 자른다")
    void clampsOutOfRange() {
        // when / then
        assertThat(Easing.easeOut(-0.3)).isEqualTo(0.0);
        assertThat(Easing.easeOut(1.7)).isEqualTo(1.0);
        assertThat(Easing.easeOut(Double.NaN)).isEqualTo(0.0);
    }

    @Property
    @Label("ease-out 은 단조 증가하고 0..1 안에 있다")
    void monotonic(@ForAll @DoubleRange(min = 0, max = 1) double a, @ForAll @DoubleRange(min = 0, max = 1) double b) {
        // given
        double lo = Math.min(a, b);
        double hi = Math.max(a, b);

        // when
        double easedLo = Easing.easeOut(lo);
        double easedHi = Easing.easeOut(hi);

        // then
        assertThat(easedLo).isLessThanOrEqualTo(easedHi);
        assertThat(easedLo).isBetween(0.0, 1.0);
        assertThat(easedHi).isBetween(0.0, 1.0);
    }
}
