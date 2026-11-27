package tanager.ui.keys;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tanager.domain.layout.Direction;
import tanager.domain.layout.Orientation;

/**
 * 레이아웃 키 표(SDD §8.2 기본값, #148). 순수 함수다 — 사용자 재정의는 M5. 이 키들은 터미널보다 먼저 가로채므로 셸(readline 의 Alt+← 등)에는
 * 가지 않는다.
 */
public final class Keymap {
    private static final int KEYBOARD_MODIFIERS = InputEvent.SHIFT_DOWN_MASK
            | InputEvent.CTRL_DOWN_MASK
            | InputEvent.ALT_DOWN_MASK
            | InputEvent.META_DOWN_MASK
            | InputEvent.ALT_GRAPH_DOWN_MASK;
    private static final int ALT = InputEvent.ALT_DOWN_MASK;
    private static final int ALT_SHIFT = InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK;
    private static final int CTRL_SHIFT = InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK;

    /**
     * 키 하나와 그 이름. 이름은 툴팁에 보인다.
     *
     * @param modifiers {@link InputEvent#getModifiersEx()} 의 키보드 수식키
     */
    public record Binding(int keyCode, int modifiers, String text, LayoutAction action) {
        public Binding {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(action, "action");
        }
    }

    // 같은 동작의 키가 여럿이면 처음 것이 툴팁에 보인다. = 와 - 는 자판 · 숫자패드에 따라 키 코드가 다르다
    private static final List<Binding> TABLE = List.of(
            new Binding(KeyEvent.VK_EQUALS, ALT_SHIFT, "Alt+Shift+=", new LayoutAction.Split(Orientation.ROW)),
            new Binding(KeyEvent.VK_PLUS, ALT_SHIFT, "Alt+Shift+=", new LayoutAction.Split(Orientation.ROW)),
            new Binding(KeyEvent.VK_ADD, ALT_SHIFT, "Alt+Shift+=", new LayoutAction.Split(Orientation.ROW)),
            new Binding(KeyEvent.VK_MINUS, ALT_SHIFT, "Alt+Shift+-", new LayoutAction.Split(Orientation.COLUMN)),
            new Binding(KeyEvent.VK_SUBTRACT, ALT_SHIFT, "Alt+Shift+-", new LayoutAction.Split(Orientation.COLUMN)),
            new Binding(KeyEvent.VK_LEFT, ALT, "Alt+←", new LayoutAction.Focus(Direction.LEFT)),
            new Binding(KeyEvent.VK_RIGHT, ALT, "Alt+→", new LayoutAction.Focus(Direction.RIGHT)),
            new Binding(KeyEvent.VK_UP, ALT, "Alt+↑", new LayoutAction.Focus(Direction.UP)),
            new Binding(KeyEvent.VK_DOWN, ALT, "Alt+↓", new LayoutAction.Focus(Direction.DOWN)),
            new Binding(KeyEvent.VK_LEFT, ALT_SHIFT, "Alt+Shift+←", new LayoutAction.Resize(Direction.LEFT)),
            new Binding(KeyEvent.VK_RIGHT, ALT_SHIFT, "Alt+Shift+→", new LayoutAction.Resize(Direction.RIGHT)),
            new Binding(KeyEvent.VK_UP, ALT_SHIFT, "Alt+Shift+↑", new LayoutAction.Resize(Direction.UP)),
            new Binding(KeyEvent.VK_DOWN, ALT_SHIFT, "Alt+Shift+↓", new LayoutAction.Resize(Direction.DOWN)),
            new Binding(KeyEvent.VK_Z, CTRL_SHIFT, "Ctrl+Shift+Z", new LayoutAction.Zoom()),
            new Binding(KeyEvent.VK_W, CTRL_SHIFT, "Ctrl+Shift+W", new LayoutAction.Close()),
            new Binding(KeyEvent.VK_LEFT, CTRL_SHIFT, "Ctrl+Shift+←", new LayoutAction.Swap(Direction.LEFT)),
            new Binding(KeyEvent.VK_RIGHT, CTRL_SHIFT, "Ctrl+Shift+→", new LayoutAction.Swap(Direction.RIGHT)));

    private Keymap() {}

    /**
     * 눌린 키의 레이아웃 동작. 수식키는 정확히 같아야 한다 — AltGr(Windows 는 Ctrl+Alt)로 치는 글자나 수식키가 더 붙은 키는 비어 셸로 간다.
     *
     * @param modifiersEx {@link InputEvent#getModifiersEx()}. 마우스 버튼 비트는 무시한다
     */
    public static Optional<LayoutAction> actionFor(int keyCode, int modifiersEx) {
        int modifiers = modifiersEx & KEYBOARD_MODIFIERS;
        for (Binding binding : TABLE) {
            if (binding.keyCode() == keyCode && binding.modifiers() == modifiers) {
                return Optional.of(binding.action());
            }
        }
        return Optional.empty();
    }

    /** {@code action} 의 키. 키가 없는 동작(위아래 교환 등 메뉴 전용)이면 비어 있다. */
    public static Optional<Binding> bindingOf(LayoutAction action) {
        Objects.requireNonNull(action, "action");
        for (Binding binding : TABLE) {
            if (binding.action().equals(action)) {
                return Optional.of(binding);
            }
        }
        return Optional.empty();
    }

    /** 툴팁에 보일 {@code action} 의 키 이름(예 {@code Alt+Shift+=}). */
    public static Optional<String> keyOf(LayoutAction action) {
        return bindingOf(action).map(Binding::text);
    }
}
