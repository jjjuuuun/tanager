package dev.tanager.domain.history;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AliasTest {

    @Test
    @DisplayName("앞뒤 공백은 벗겨서 만든다")
    void stripsSurroundingWhitespace() {
        // given / when
        var r = Alias.of("  Tanager  ");
        // then
        assertThat(r.isOk()).isTrue();
        assertThat(r.value().orElseThrow().value()).isEqualTo("Tanager");
    }

    @Test
    @DisplayName("64 코드 포인트까지는 허용한다")
    void accepts64CodePoints() {
        // given
        String s = "가".repeat(64);
        // when / then
        assertThat(Alias.of(s).isOk()).isTrue();
    }

    @Test
    @DisplayName("65 코드 포인트는 InvalidAlias 다")
    void rejects65CodePoints() {
        // given / when
        var r = Alias.of("a".repeat(65));
        // then
        assertThat(r.error().orElseThrow()).isInstanceOf(HistoryError.InvalidAlias.class);
    }

    @Test
    @DisplayName("제어 문자가 들어 있으면 InvalidAlias 다")
    void rejectsControlCharacters() {
        // given / when / then
        assertThat(Alias.of("a\nb").error()).isPresent();
        assertThat(Alias.of("a\tb").error()).isPresent();
        assertThat(Alias.of("a\u0000b").error()).isPresent();
    }

    @Test
    @DisplayName("빈 값은 별칭이 될 수 없다(지움은 호출 쪽 몫이다)")
    void rejectsBlank() {
        // given / when / then
        assertThat(Alias.of("   ").error()).isPresent();
    }
}
