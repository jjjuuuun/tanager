package tanager.ui.keys;

import static java.awt.event.InputEvent.ALT_DOWN_MASK;
import static java.awt.event.InputEvent.CTRL_DOWN_MASK;
import static java.awt.event.InputEvent.SHIFT_DOWN_MASK;
import static org.assertj.core.api.Assertions.assertThat;

import java.awt.event.KeyEvent;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class KeyTest {

    @Test
    @DisplayName("KeyStroke 형식의 원문을 키 코드와 수식키로 읽는다")
    void parse() {
        // when
        Optional<Key> key = Key.parse("alt shift EQUALS");

        // then
        assertThat(key).contains(new Key(KeyEvent.VK_EQUALS, ALT_DOWN_MASK | SHIFT_DOWN_MASK));
    }

    @Test
    @DisplayName("키 이름의 대소문자는 가리지 않는다 — Ctrl Shift d 도 Ctrl+Shift+D 다")
    void lenientCase() {
        // when
        Optional<Key> key = Key.parse("Ctrl Shift d");

        // then
        assertThat(key).contains(new Key(KeyEvent.VK_D, CTRL_DOWN_MASK | SHIFT_DOWN_MASK));
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(strings = {"foo bar", "ctrl+shift+D", "typed a", "released ctrl B", "ctrl", "", "   "})
    @DisplayName("읽을 수 없는 원문 · 글자 입력(typed) · 뗄 때(released)는 비어 있다")
    void unparseable(String raw) {
        // when
        Optional<Key> key = Key.parse(raw);

        // then
        assertThat(key).isEmpty();
    }

    @ParameterizedTest(name = "[{index}] {0} → {1}")
    @CsvSource(
            delimiter = '|',
            value = {
                "alt shift EQUALS | Alt+Shift+=",
                "alt shift MINUS | Alt+Shift+-",
                "alt LEFT | Alt+←",
                "alt shift DOWN | Alt+Shift+↓",
                "ctrl shift Z | Ctrl+Shift+Z",
                "ctrl COMMA | Ctrl+,",
                "shift alt ctrl D | Ctrl+Alt+Shift+D",
                "ctrl F5 | Ctrl+F5"
            })
    @DisplayName("툴팁에 보일 키 이름은 Ctrl · Alt · Shift 순서에 글자 · 화살표를 붙인다")
    void text(String raw, String expected) {
        // when
        String text = Key.parse(raw).orElseThrow().text();

        // then
        assertThat(text).isEqualTo(expected);
    }
}
