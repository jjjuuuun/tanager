package tanager.domain.toolkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 공급자 식별자의 형식(#204). */
class ProviderIdTest {

    @Test
    @DisplayName("소문자 · 숫자 · 하이픈으로 된 식별자를 받는다")
    void acceptsKebabCase() {
        // given
        String raw = "claude-code";

        // when
        ProviderId id = new ProviderId(raw);

        // then
        assertThat(id.value()).isEqualTo(raw);
        assertThat(new ProviderId("a").value()).isEqualTo("a");
        assertThat(new ProviderId("0" + "a".repeat(31)).value()).hasSize(32);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Claude Code", "", "-claude", "claude_code", "CLAUDE", "가나"})
    @DisplayName("대문자 · 공백 · 하이픈 시작 · 밑줄 · 비ASCII 는 거절한다")
    void rejectsOtherForms(String raw) {
        assertThatIllegalArgumentException().isThrownBy(() -> new ProviderId(raw));
    }

    @Test
    @DisplayName("33자 이상은 거절한다")
    void rejectsTooLong() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ProviderId("a".repeat(33)));
    }
}
