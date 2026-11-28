package tanager.ui.keys;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Map;
import java.util.Optional;

/**
 * SDD §8.2 의 prefix 열(prefix 다음 키 → 동작, #193). 동작은 {@link ActionIds} 의 id 로 적는다 — 탭(#181) · 명령 팔레트가 생기면 그 id 를
 * 더한다({@code c} → {@code tab.new} 등).
 */
final class PrefixTable {
    private static final int CTRL = InputEvent.CTRL_DOWN_MASK;

    // 누를 때 판정하는 키: 글자를 만들지 않는다
    private static final Map<Key, KeyAction> PRESSED = Map.of(
            new Key(KeyEvent.VK_LEFT, 0), action("pane.focusLeft"),
            new Key(KeyEvent.VK_RIGHT, 0), action("pane.focusRight"),
            new Key(KeyEvent.VK_UP, 0), action("pane.focusUp"),
            new Key(KeyEvent.VK_DOWN, 0), action("pane.focusDown"),
            new Key(KeyEvent.VK_LEFT, CTRL), action("pane.resizeLeft"),
            new Key(KeyEvent.VK_RIGHT, CTRL), action("pane.resizeRight"),
            new Key(KeyEvent.VK_UP, CTRL), action("pane.resizeUp"),
            new Key(KeyEvent.VK_DOWN, CTRL), action("pane.resizeDown"));

    // 글자로 판정하는 키: 자판마다 키 코드 · 수식키가 다르다(% 는 US 자판에서 Shift+5)
    private static final Map<Character, KeyAction> TYPED = Map.of(
            '%', action("pane.splitRight"),
            '"', action("pane.splitDown"),
            'z', action("pane.zoom"),
            'x', action("pane.close"),
            '{', action("pane.swapLeft"),
            '}', action("pane.swapRight"));

    private PrefixTable() {}

    /** 누른 키(키 코드 · 키보드 수식키)의 동작. */
    static Optional<KeyAction> pressed(int keyCode, int modifiers) {
        if (keyCode == KeyEvent.VK_UNDEFINED) {
            return Optional.empty();
        }
        return Optional.ofNullable(PRESSED.get(Key.pressed(keyCode, modifiers)));
    }

    /** 친 글자의 동작. */
    static Optional<KeyAction> typed(char character) {
        return Optional.ofNullable(TYPED.get(character));
    }

    private static KeyAction action(String id) {
        return ActionIds.actionOf(id).orElseThrow(() -> new IllegalStateException("prefix 열의 동작 id 가 없다: " + id));
    }
}
