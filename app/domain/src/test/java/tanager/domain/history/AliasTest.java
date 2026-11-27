package tanager.domain.history;

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
    @DisplayName("길이는 UTF-16 단위가 아니라 코드 포인트로 센다 — 보조 평면 문자 64개는 허용, 65개는 거부")
    void countsSupplementaryCharactersAsOne() {
        // given
        String smile = "\uD83D\uDE00";
        // when / then
        assertThat(Alias.of(smile.repeat(64)).isOk()).isTrue();
        assertThat(Alias.of(smile.repeat(65)).error()).isPresent();
    }

    @Test
    @DisplayName("방향 제어 · 폭 없는 문자 · 줄 구분자 같은 서식 문자, NBSP 뿐인 값은 InvalidAlias 다 — 트리 표시가 비거나 뒤집히지 않게")
    void rejectsFormatCharactersAndInvisibleOnly() {
        // given / when / then
        assertThat(Alias.of("a\u202Eb").error()).isPresent();
        assertThat(Alias.of("a\u200Bb").error()).isPresent();
        assertThat(Alias.of("a\u2028b").error()).isPresent();
        assertThat(Alias.of("\u00A0\u00A0").error()).isPresent();
        assertThat(Alias.of("a\u00A0b").isOk()).isTrue();
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
