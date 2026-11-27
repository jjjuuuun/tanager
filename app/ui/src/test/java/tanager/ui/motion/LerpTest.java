package tanager.ui.motion;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LerpTest {

    @Test
    @DisplayName("실수 보간은 0 이면 시작, 1 이면 끝, 0.5 면 가운데다")
    void numberEndpointsAndMiddle() {
        // when / then
        assertThat(Lerp.number(2, 10, 0)).isEqualTo(2.0);
        assertThat(Lerp.number(2, 10, 1)).isEqualTo(10.0);
        assertThat(Lerp.number(2, 10, 0.5)).isEqualTo(6.0);
        assertThat(Lerp.number(10, 2, 0.25)).isEqualTo(8.0);
    }

    @Test
    @DisplayName("정수 보간은 반올림하고 끝에서 정확히 끝 값이다")
    void integerRounds() {
        // when / then
        assertThat(Lerp.integer(0, 240, 0)).isZero();
        assertThat(Lerp.integer(0, 240, 1)).isEqualTo(240);
        assertThat(Lerp.integer(0, 3, 0.5)).isEqualTo(2);
        assertThat(Lerp.integer(240, 0, 0.5)).isEqualTo(120);
    }

    @Test
    @DisplayName("진행률이 범위를 벗어나면 0..1 로 자른다")
    void clampsFraction() {
        // when / then
        assertThat(Lerp.number(0, 10, -1)).isEqualTo(0.0);
        assertThat(Lerp.number(0, 10, 2)).isEqualTo(10.0);
        assertThat(Lerp.integer(0, 10, 2)).isEqualTo(10);
    }

    @Property
    @Label("정수 보간 값은 항상 두 끝 값 사이에 있다")
    void integerStaysBetween(
            @ForAll @IntRange(min = -5000, max = 5000) int from,
            @ForAll @IntRange(min = -5000, max = 5000) int to,
            @ForAll @DoubleRange(min = 0, max = 1) double fraction) {
        // when
        int value = Lerp.integer(from, to, fraction);

        // then
        assertThat(value).isBetween(Math.min(from, to), Math.max(from, to));
    }
}
