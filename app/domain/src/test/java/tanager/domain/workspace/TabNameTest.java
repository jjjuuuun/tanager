package tanager.domain.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 사용자가 붙인 탭 이름(#176, FR-LAY-07). */
class TabNameTest {

    @Test
    @DisplayName("앞뒤 공백을 떼고 만든다")
    void stripsSurroundingWhitespace() {
        // when
        TabName name = TabName.of("  빌드 서버 \t").value().orElseThrow();

        // then
        assertThat(name.value()).isEqualTo("빌드 서버");
    }

    @Test
    @DisplayName("비었거나 공백뿐이면 이름이 비었다는 문제다")
    void blank() {
        assertThat(TabName.of("  ").error()).contains(new TabNameProblem.Blank());
        assertThat(TabName.of("").error()).contains(new TabNameProblem.Blank());
    }

    @Test
    @DisplayName("길이는 코드 포인트로 세어 40 까지 허용하고, 넘으면 TooLong 이다")
    void lengthInCodePoints() {
        // given: 이모지는 char 2개지만 코드 포인트 1개
        String forty = "😀".repeat(TabName.MAX_LENGTH);

        // then
        assertThat(TabName.of(forty).value().orElseThrow().value()).isEqualTo(forty);
        assertThat(TabName.of("가".repeat(TabName.MAX_LENGTH + 1)).error())
                .contains(new TabNameProblem.TooLong(TabName.MAX_LENGTH + 1));
    }

    @Test
    @DisplayName("가운데에 제어 문자가 있으면 ControlCharacter 다")
    void controlCharacter() {
        assertThat(TabName.of("a\nb").error()).contains(new TabNameProblem.ControlCharacter('\n'));
        assertThat(TabName.of("a\u0007b").error()).contains(new TabNameProblem.ControlCharacter(0x07));
    }

    @Test
    @DisplayName("생성자는 불변식을 어긴 값(앞뒤 공백 · 빈 값 · 너무 긴 값 · 제어 문자)을 거부한다")
    void constructorInvariants() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TabName(" a"));
        assertThatIllegalArgumentException().isThrownBy(() -> new TabName(""));
        assertThatIllegalArgumentException().isThrownBy(() -> new TabName("a".repeat(TabName.MAX_LENGTH + 1)));
        assertThatIllegalArgumentException().isThrownBy(() -> new TabName("a\tb"));
        assertThat(new TabName("작업").value()).isEqualTo("작업");
    }
}
