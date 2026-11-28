package tanager.domain.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ShellLineTest {

    @Test
    @DisplayName("셸에 입력할 한 줄을 그대로 담는다")
    void keepsText() {
        // given / when
        var line = new ShellLine("claude --resume abc");
        // then
        assertThat(line.text()).isEqualTo("claude --resume abc");
    }

    @Test
    @DisplayName("길이는 코드 포인트로 센다 — 보조 평면 문자 512개는 받는다")
    void accepts512CodePoints() {
        // given
        String smile = "😀";
        // when / then
        assertThat(new ShellLine(smile.repeat(512)).text()).hasSize(1024);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a\nb", "a\rb", "a\tb", "a\u0000b", "a\u007fb", "a\u001b[31m", "a\u0085b"})
    @DisplayName("빈 값과 제어 문자(줄바꿈 포함)는 거절한다 — 줄바꿈이 들어가면 셸이 바로 실행한다")
    void rejectsControlCharacters(String raw) {
        // given / when / then
        assertThatThrownBy(() -> new ShellLine(raw)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("513 코드 포인트는 거절한다")
    void rejectsTooLong() {
        // given
        String raw = "가".repeat(513);
        // when / then
        assertThatThrownBy(() -> new ShellLine(raw)).isInstanceOf(IllegalArgumentException.class);
    }
}
