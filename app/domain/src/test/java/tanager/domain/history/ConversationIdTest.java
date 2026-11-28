package tanager.domain.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConversationIdTest {

    @Test
    @DisplayName("UUID 모양의 ID 는 그대로 받는다")
    void acceptsUuid() {
        // given
        String raw = "0f8e2c1a-1111-4222-8333-944455556666";
        // when
        var id = new ConversationId(raw);
        // then
        assertThat(id.value()).isEqualTo(raw);
    }

    @Test
    @DisplayName("128자까지는 받는다")
    void accepts128Characters() {
        // given / when / then
        assertThat(new ConversationId("a".repeat(128)).value()).hasSize(128);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc;rm -rf /", "", "a b", "../evil", "a\nb", "가나다", "a.b"})
    @DisplayName("명령줄에 들어가므로 [A-Za-z0-9_-] 밖의 글자가 있으면 거절한다")
    void rejectsUnsafeCharacters(String raw) {
        // given / when / then
        assertThatThrownBy(() -> new ConversationId(raw)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ConversationId.parse(raw)).isEmpty();
    }

    @Test
    @DisplayName("129자는 거절한다")
    void rejectsTooLong() {
        // given
        String raw = "a".repeat(129);
        // when / then
        assertThatThrownBy(() -> new ConversationId(raw)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ConversationId.parse(raw)).isEmpty();
    }

    @Test
    @DisplayName("parse 는 맞는 값이면 그 ID 를 준다")
    void parsesValidValue() {
        // given / when / then
        assertThat(ConversationId.parse("abc_DEF-123")).contains(new ConversationId("abc_DEF-123"));
    }
}
