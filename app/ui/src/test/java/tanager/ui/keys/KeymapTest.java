package tanager.ui.keys;

import static java.awt.event.InputEvent.ALT_DOWN_MASK;
import static java.awt.event.InputEvent.ALT_GRAPH_DOWN_MASK;
import static java.awt.event.InputEvent.BUTTON1_DOWN_MASK;
import static java.awt.event.InputEvent.CTRL_DOWN_MASK;
import static java.awt.event.InputEvent.META_DOWN_MASK;
import static java.awt.event.InputEvent.SHIFT_DOWN_MASK;
import static org.assertj.core.api.Assertions.assertThat;

import java.awt.event.KeyEvent;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tanager.domain.layout.Direction;
import tanager.domain.layout.Orientation;

class KeymapTest {
    private static final int ALT_SHIFT = ALT_DOWN_MASK | SHIFT_DOWN_MASK;
    private static final int CTRL_SHIFT = CTRL_DOWN_MASK | SHIFT_DOWN_MASK;

    static Stream<Arguments> table() {
        return Stream.of(
                Arguments.of(KeyEvent.VK_EQUALS, ALT_SHIFT, new LayoutAction.Split(Orientation.ROW)),
                Arguments.of(KeyEvent.VK_PLUS, ALT_SHIFT, new LayoutAction.Split(Orientation.ROW)),
                Arguments.of(KeyEvent.VK_ADD, ALT_SHIFT, new LayoutAction.Split(Orientation.ROW)),
                Arguments.of(KeyEvent.VK_MINUS, ALT_SHIFT, new LayoutAction.Split(Orientation.COLUMN)),
                Arguments.of(KeyEvent.VK_SUBTRACT, ALT_SHIFT, new LayoutAction.Split(Orientation.COLUMN)),
                Arguments.of(KeyEvent.VK_LEFT, ALT_DOWN_MASK, new LayoutAction.Focus(Direction.LEFT)),
                Arguments.of(KeyEvent.VK_RIGHT, ALT_DOWN_MASK, new LayoutAction.Focus(Direction.RIGHT)),
                Arguments.of(KeyEvent.VK_UP, ALT_DOWN_MASK, new LayoutAction.Focus(Direction.UP)),
                Arguments.of(KeyEvent.VK_DOWN, ALT_DOWN_MASK, new LayoutAction.Focus(Direction.DOWN)),
                Arguments.of(KeyEvent.VK_LEFT, ALT_SHIFT, new LayoutAction.Resize(Direction.LEFT)),
                Arguments.of(KeyEvent.VK_RIGHT, ALT_SHIFT, new LayoutAction.Resize(Direction.RIGHT)),
                Arguments.of(KeyEvent.VK_UP, ALT_SHIFT, new LayoutAction.Resize(Direction.UP)),
                Arguments.of(KeyEvent.VK_DOWN, ALT_SHIFT, new LayoutAction.Resize(Direction.DOWN)),
                Arguments.of(KeyEvent.VK_Z, CTRL_SHIFT, new LayoutAction.Zoom()),
                Arguments.of(KeyEvent.VK_W, CTRL_SHIFT, new LayoutAction.Close()),
                Arguments.of(KeyEvent.VK_LEFT, CTRL_SHIFT, new LayoutAction.Swap(Direction.LEFT)),
                Arguments.of(KeyEvent.VK_RIGHT, CTRL_SHIFT, new LayoutAction.Swap(Direction.RIGHT)));
    }

    @ParameterizedTest(name = "[{index}] {2}")
    @MethodSource("table")
    @DisplayName("표의 키는 그 동작이다(SDD §8.2)")
    void tableKeys(int keyCode, int modifiers, LayoutAction expected) {
        // when
        Optional<KeyAction> action = Keymap.defaults().actionFor(keyCode, modifiers);

        // then
        assertThat(action).contains(expected);
    }

    @Test
    @DisplayName("마우스 버튼을 누른 채여도 키보드 수식키만 본다")
    void mouseButtonsIgnored() {
        // when
        Optional<KeyAction> action = Keymap.defaults().actionFor(KeyEvent.VK_LEFT, ALT_DOWN_MASK | BUTTON1_DOWN_MASK);

        // then
        assertThat(action).contains(new LayoutAction.Focus(Direction.LEFT));
    }

    static Stream<Arguments> notOurs() {
        return Stream.of(
                // 셸로 가야 하는 키
                Arguments.of(KeyEvent.VK_B, ALT_DOWN_MASK),
                Arguments.of(KeyEvent.VK_F, ALT_DOWN_MASK),
                Arguments.of(KeyEvent.VK_LEFT, 0),
                Arguments.of(KeyEvent.VK_LEFT, CTRL_DOWN_MASK),
                Arguments.of(KeyEvent.VK_LEFT, SHIFT_DOWN_MASK),
                Arguments.of(KeyEvent.VK_EQUALS, 0),
                Arguments.of(KeyEvent.VK_EQUALS, ALT_DOWN_MASK),
                Arguments.of(KeyEvent.VK_MINUS, SHIFT_DOWN_MASK),
                Arguments.of(KeyEvent.VK_Z, CTRL_DOWN_MASK),
                Arguments.of(KeyEvent.VK_W, CTRL_DOWN_MASK),
                Arguments.of(KeyEvent.VK_UP, CTRL_SHIFT),
                Arguments.of(KeyEvent.VK_DOWN, CTRL_SHIFT),
                // AltGr(Windows 는 Ctrl+Alt 로 온다) — 다른 자판의 글자 입력이다
                Arguments.of(KeyEvent.VK_LEFT, CTRL_DOWN_MASK | ALT_DOWN_MASK),
                Arguments.of(KeyEvent.VK_EQUALS, CTRL_DOWN_MASK | ALT_DOWN_MASK | SHIFT_DOWN_MASK),
                Arguments.of(KeyEvent.VK_LEFT, ALT_GRAPH_DOWN_MASK),
                Arguments.of(KeyEvent.VK_MINUS, ALT_GRAPH_DOWN_MASK | SHIFT_DOWN_MASK),
                // 수식키가 더 붙으면 다른 키다
                Arguments.of(KeyEvent.VK_Z, CTRL_SHIFT | ALT_DOWN_MASK),
                Arguments.of(KeyEvent.VK_LEFT, ALT_DOWN_MASK | META_DOWN_MASK));
    }

    @ParameterizedTest(name = "[{index}] key={0} mods={1}")
    @MethodSource("notOurs")
    @DisplayName("표에 없는 키는 비어 셸로 간다")
    void otherKeysAreEmpty(int keyCode, int modifiers) {
        // when
        Optional<KeyAction> action = Keymap.defaults().actionFor(keyCode, modifiers);

        // then
        assertThat(action).isEmpty();
    }

    static Stream<Arguments> labels() {
        return Stream.of(
                Arguments.of(new LayoutAction.Split(Orientation.ROW), "Alt+Shift+="),
                Arguments.of(new LayoutAction.Split(Orientation.COLUMN), "Alt+Shift+-"),
                Arguments.of(new LayoutAction.Focus(Direction.LEFT), "Alt+←"),
                Arguments.of(new LayoutAction.Focus(Direction.UP), "Alt+↑"),
                Arguments.of(new LayoutAction.Resize(Direction.DOWN), "Alt+Shift+↓"),
                Arguments.of(new LayoutAction.Zoom(), "Ctrl+Shift+Z"),
                Arguments.of(new LayoutAction.Close(), "Ctrl+Shift+W"),
                Arguments.of(new LayoutAction.Swap(Direction.RIGHT), "Ctrl+Shift+→"));
    }

    @ParameterizedTest(name = "[{index}] {1}")
    @MethodSource("labels")
    @DisplayName("툴팁에 보일 키 이름")
    void keyLabel(LayoutAction action, String expected) {
        // when
        Optional<String> label = Keymap.defaults().keyOf(action);

        // then
        assertThat(label).contains(expected);
    }

    @Test
    @DisplayName("키가 없는 동작(위아래 교환)은 키 이름이 비어 있다")
    void noKeyForVerticalSwap() {
        // when
        Optional<String> label = Keymap.defaults().keyOf(new LayoutAction.Swap(Direction.UP));

        // then
        assertThat(label).isEmpty();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("labels")
    @DisplayName("키 이름이 있는 동작은 그 키로 돌아온다(표와 이름이 어긋나지 않게)")
    void bindingRoundTrip(LayoutAction action, String label) {
        // given
        Keymap.Binding binding = Keymap.defaults().bindingOf(action).orElseThrow();

        // when
        Optional<KeyAction> back = Keymap.defaults().actionFor(binding.keyCode(), binding.modifiers());

        // then
        assertThat(back).contains(action);
        assertThat(binding.text()).isEqualTo(label);
    }
}
