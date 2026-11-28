package tanager.ui.keys;

import java.awt.event.KeyEvent;
import java.util.Optional;

/**
 * 키 이벤트 하나에서 판정에 쓰는 것만(#193). 누름은 키 코드 · 키보드 수식키, 글자는 자판이 만든 글자다 — prefix 열의 {@code %} · {@code "}
 * 처럼 자판마다 키 코드가 다른 키는 글자로 판정한다.
 */
public sealed interface KeyInput {

    /** {@link KeyEvent#KEY_PRESSED}. {@code modifiers} 는 키보드 수식키만 남긴다(마우스 버튼 비트는 버린다). */
    record Pressed(int keyCode, int modifiers) implements KeyInput {
        public Pressed {
            modifiers &= Key.KEYBOARD_MODIFIERS;
        }
    }

    /** {@link KeyEvent#KEY_TYPED}. */
    record Typed(char character) implements KeyInput {}

    /** {@link KeyEvent#KEY_RELEASED}. */
    record Released(int keyCode) implements KeyInput {}

    /** {@code event} 의 입력. 키 이벤트 종류가 아니면 비어 있다. */
    static Optional<KeyInput> of(KeyEvent event) {
        return switch (event.getID()) {
            case KeyEvent.KEY_PRESSED -> Optional.of(new Pressed(event.getKeyCode(), event.getModifiersEx()));
            case KeyEvent.KEY_TYPED -> Optional.of(new Typed(event.getKeyChar()));
            case KeyEvent.KEY_RELEASED -> Optional.of(new Released(event.getKeyCode()));
            default -> Optional.empty();
        };
    }
}
