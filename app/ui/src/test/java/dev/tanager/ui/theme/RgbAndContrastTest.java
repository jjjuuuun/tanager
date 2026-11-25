package dev.tanager.ui.theme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RgbAndContrastTest {
    private static final Rgb BLACK = Rgb.parse("#000000");
    private static final Rgb WHITE = Rgb.parse("#FFFFFF");

    @Test
    @DisplayName("#RRGGBB 문자열을 Rgb 로 읽는다(대소문자 무시)")
    void parsesHex() {
        // given / when
        Rgb rgb = Rgb.parse("#1b1E26");

        // then
        assertThat(rgb).isEqualTo(new Rgb(0x1B, 0x1E, 0x26));
    }

    @Test
    @DisplayName("#RRGGBB 형식이 아니면 IllegalArgumentException")
    void rejectsMalformedHex() {
        assertThatIllegalArgumentException().isThrownBy(() -> Rgb.parse("#12345"));
        assertThatIllegalArgumentException().isThrownBy(() -> Rgb.parse("red"));
        assertThatIllegalArgumentException().isThrownBy(() -> Rgb.parse("1B1E26"));
        assertThatIllegalArgumentException().isThrownBy(() -> Rgb.parse("#GGGGGG"));
    }

    @Test
    @DisplayName("검정과 흰색의 대비는 21 이다")
    void blackOnWhiteIs21() {
        assertThat(Contrast.ratio(BLACK, WHITE)).isCloseTo(21.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @DisplayName("같은 색끼리의 대비는 1 이다")
    void sameColorIs1() {
        Rgb c = Rgb.parse("#7AA2F7");

        assertThat(Contrast.ratio(c, c)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("상대 휘도는 검정 0, 흰색 1 이다")
    void luminanceBounds() {
        assertThat(BLACK.relativeLuminance()).isZero();
        assertThat(WHITE.relativeLuminance()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("WCAG 기준값: #767676/흰색 ≈ 4.54(통과), #777777/흰색 ≈ 4.48(미달)")
    void wcagReferenceRatios() {
        // given
        Rgb passes = Rgb.parse("#767676");
        Rgb fails = Rgb.parse("#777777");

        // when / then
        assertThat(Contrast.ratio(passes, WHITE))
                .isCloseTo(4.54, org.assertj.core.data.Offset.offset(0.01))
                .isGreaterThanOrEqualTo(4.5);
        assertThat(Contrast.ratio(fails, WHITE))
                .isCloseTo(4.48, org.assertj.core.data.Offset.offset(0.01))
                .isLessThan(4.5);
    }

    @Test
    @DisplayName("중간 회색 #808080 의 상대 휘도는 약 0.2159 이다(sRGB 감마)")
    void midGrayLuminance() {
        assertThat(Rgb.parse("#808080").relativeLuminance())
                .isCloseTo(0.2159, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Property
    @Label("대비는 대칭이며 1 이상 21 이하이다")
    void ratioIsSymmetricAndBounded(
            @ForAll @IntRange(min = 0, max = 255) int r1,
            @ForAll @IntRange(min = 0, max = 255) int g1,
            @ForAll @IntRange(min = 0, max = 255) int b1,
            @ForAll @IntRange(min = 0, max = 255) int r2,
            @ForAll @IntRange(min = 0, max = 255) int g2,
            @ForAll @IntRange(min = 0, max = 255) int b2) {
        Rgb a = new Rgb(r1, g1, b1);
        Rgb b = new Rgb(r2, g2, b2);

        assertThat(Contrast.ratio(a, b)).isEqualTo(Contrast.ratio(b, a));
        assertThat(Contrast.ratio(a, b)).isBetween(1.0, 21.0);
    }

    @Test
    @DisplayName("범위 밖 채널 값은 거부한다")
    void rejectsOutOfRangeChannel() {
        assertThatThrownBy(() -> new Rgb(256, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Rgb(0, -1, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
