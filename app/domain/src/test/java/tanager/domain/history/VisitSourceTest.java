package tanager.domain.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VisitSourceTest {

    @Test
    @DisplayName("셸과 Claude Code 출처를 상수로 둔다")
    void constants() {
        // given / when / then
        assertThat(VisitSource.SHELL.id()).isEqualTo("shell");
        assertThat(VisitSource.CLAUDE_CODE.id()).isEqualTo("claude-code");
    }

    @Test
    @DisplayName("모르는 출처(다음 버전이 쓴 값)도 형식이 맞으면 담는다")
    void acceptsUnknownButWellFormed() {
        // given / when / then
        assertThat(new VisitSource("future-tool").id()).isEqualTo("future-tool");
        assertThat(new VisitSource("a".repeat(32)).id()).hasSize(32);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Bad Value", "-shell", "Shell", "a_b"})
    @DisplayName("[a-z0-9][a-z0-9-]{0,31} 밖의 값은 거절한다")
    void rejectsMalformed(String raw) {
        // given / when / then
        assertThatThrownBy(() -> new VisitSource(raw)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("33자는 거절한다")
    void rejectsTooLong() {
        // given
        String raw = "a".repeat(33);
        // when / then
        assertThatThrownBy(() -> new VisitSource(raw)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("parse 는 형식이 맞으면 출처를, 틀리면 empty 를 돌려준다 — 저장 파일의 틀린 원소만 버리려고")
    void parse() {
        // given / when / then
        assertThat(VisitSource.parse("future-tool")).contains(new VisitSource("future-tool"));
        assertThat(VisitSource.parse("Bad Value")).isEmpty();
        assertThat(VisitSource.parse("")).isEmpty();
    }
}
