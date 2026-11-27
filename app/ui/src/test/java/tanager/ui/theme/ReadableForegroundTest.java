package tanager.ui.theme;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 배경 위에서 읽히지 않는 글자색을 배경 반대쪽으로 보정한다(#113). */
class ReadableForegroundTest {
    private static final Rgb BLUE = Rgb.parse("#7AA2F7");
    private static final Rgb GREEN = Rgb.parse("#8CC6A0");
    private static final Rgb DARK = Rgb.parse("#1B1E26");
    private static final Rgb NAVY = Rgb.parse("#20304A");

    @Test
    @DisplayName("이미 기준 대비 이상이면 글자색을 그대로 둔다")
    void keepsReadableForeground() {
        // given
        assertThat(Contrast.ratio(BLUE, DARK)).isGreaterThanOrEqualTo(ReadableForeground.MIN_CONTRAST);

        // when
        Rgb result = ReadableForeground.on(BLUE, DARK);

        // then
        assertThat(result).isEqualTo(BLUE);
    }

    @Test
    @DisplayName("밝은 배경 위의 읽히지 않는 글자는 어둡게 보정해 기준 대비에 맞춘다")
    void darkensOnLightBackground() {
        // given: 파랑 글자 / 초록 배경 = 1.28 : 1
        assertThat(Contrast.ratio(BLUE, GREEN)).isLessThan(ReadableForeground.MIN_CONTRAST);

        // when
        Rgb result = ReadableForeground.on(BLUE, GREEN);

        // then
        assertThat(Contrast.ratio(result, GREEN)).isGreaterThanOrEqualTo(ReadableForeground.MIN_CONTRAST);
        assertThat(result.relativeLuminance()).isLessThan(BLUE.relativeLuminance());
    }

    @Test
    @DisplayName("어두운 배경 위의 읽히지 않는 글자는 밝게 보정한다")
    void lightensOnDarkBackground() {
        // given: 남색 글자 / 어두운 배경
        assertThat(Contrast.ratio(NAVY, DARK)).isLessThan(ReadableForeground.MIN_CONTRAST);

        // when
        Rgb result = ReadableForeground.on(NAVY, DARK);

        // then
        assertThat(Contrast.ratio(result, DARK)).isGreaterThanOrEqualTo(ReadableForeground.MIN_CONTRAST);
        assertThat(result.relativeLuminance()).isGreaterThan(NAVY.relativeLuminance());
    }

    @Test
    @DisplayName("같은 입력은 언제나 같은 결과다")
    void isDeterministic() {
        // when
        Rgb first = ReadableForeground.on(BLUE, GREEN);
        Rgb second = ReadableForeground.on(BLUE, GREEN);

        // then
        assertThat(second).isEqualTo(first);
    }
}
