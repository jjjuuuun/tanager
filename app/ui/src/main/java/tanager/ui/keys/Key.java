package tanager.ui.keys;

import java.awt.AWTKeyStroke;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import javax.swing.KeyStroke;

/**
 * 누르는 키 하나: 키 코드와 키보드 수식키({@link InputEvent#getModifiersEx()} 형식).
 *
 * @param modifiers 키보드 수식키만. 마우스 버튼 비트는 없다
 */
public record Key(int keyCode, int modifiers) {
    /** 키 판정에 쓰는 수식키. 그 밖의 비트(마우스 버튼)는 무시한다. */
    static final int KEYBOARD_MODIFIERS = InputEvent.SHIFT_DOWN_MASK
            | InputEvent.CTRL_DOWN_MASK
            | InputEvent.ALT_DOWN_MASK
            | InputEvent.META_DOWN_MASK
            | InputEvent.ALT_GRAPH_DOWN_MASK;

    public Key {
        if (keyCode == KeyEvent.VK_UNDEFINED) {
            throw new IllegalArgumentException("키 코드가 없다");
        }
        if ((modifiers & ~KEYBOARD_MODIFIERS) != 0) {
            throw new IllegalArgumentException("키보드 수식키가 아닌 비트가 있다: " + modifiers);
        }
    }

    /** 눌린 키. 마우스 버튼 비트는 버린다. */
    static Key pressed(int keyCode, int modifiersEx) {
        return new Key(keyCode, modifiersEx & KEYBOARD_MODIFIERS);
    }

    /**
     * {@link KeyStroke#getKeyStroke(String)} 형식의 원문(예 {@code "alt shift EQUALS"})을 읽는다. 수식키 · 키 이름의 대소문자는 가리지
     * 않는다. 누를 때의 키만 받는다 — {@code typed} · {@code released} 나 읽을 수 없는 원문이면 비어 있다.
     */
    public static Optional<Key> parse(String raw) {
        String[] words = raw.trim().split("\\s+");
        if (words.length == 0 || words[0].isEmpty()) {
            return Optional.empty();
        }
        String[] modifiers = Arrays.copyOf(words, words.length - 1);
        for (int i = 0; i < modifiers.length; i++) {
            modifiers[i] = modifiers[i].toLowerCase(Locale.ROOT);
        }
        if (Arrays.asList(modifiers).contains("typed")
                || Arrays.asList(modifiers).contains("released")) {
            return Optional.empty();
        }
        String normalized = String.join(" ", modifiers) + " " + words[words.length - 1].toUpperCase(Locale.ROOT);
        KeyStroke stroke;
        try {
            stroke = KeyStroke.getKeyStroke(normalized.trim());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (stroke == null
                || stroke.getKeyEventType() != KeyEvent.KEY_PRESSED
                || stroke.getKeyCode() == KeyEvent.VK_UNDEFINED) {
            return Optional.empty();
        }
        return Optional.of(pressed(stroke.getKeyCode(), stroke.getModifiers()));
    }

    /** 툴팁에 보일 이름(예 {@code Alt+Shift+=}). 수식키는 Ctrl · Alt · AltGr · Shift · Meta 순서다. */
    public String text() {
        StringBuilder text = new StringBuilder();
        append(text, InputEvent.CTRL_DOWN_MASK, "Ctrl");
        append(text, InputEvent.ALT_DOWN_MASK, "Alt");
        append(text, InputEvent.ALT_GRAPH_DOWN_MASK, "AltGr");
        append(text, InputEvent.SHIFT_DOWN_MASK, "Shift");
        append(text, InputEvent.META_DOWN_MASK, "Meta");
        return text.append(name(keyCode)).toString();
    }

    /** {@code mask} 의 수식키가 모두 눌렸다. */
    boolean has(int mask) {
        return (modifiers & mask) == mask;
    }

    private void append(StringBuilder text, int mask, String name) {
        if (has(mask)) {
            text.append(name).append('+');
        }
    }

    private static String name(int keyCode) {
        return switch (keyCode) {
            case KeyEvent.VK_LEFT -> "←";
            case KeyEvent.VK_UP -> "↑";
            case KeyEvent.VK_RIGHT -> "→";
            case KeyEvent.VK_DOWN -> "↓";
            case KeyEvent.VK_EQUALS -> "=";
            case KeyEvent.VK_MINUS -> "-";
            case KeyEvent.VK_PLUS -> "+";
            case KeyEvent.VK_COMMA -> ",";
            case KeyEvent.VK_PERIOD -> ".";
            case KeyEvent.VK_SLASH -> "/";
            case KeyEvent.VK_SEMICOLON -> ";";
            case KeyEvent.VK_QUOTE -> "'";
            case KeyEvent.VK_OPEN_BRACKET -> "[";
            case KeyEvent.VK_CLOSE_BRACKET -> "]";
            case KeyEvent.VK_BACK_SLASH -> "\\";
            case KeyEvent.VK_BACK_QUOTE -> "`";
            default -> vkName(keyCode);
        };
    }

    // KeyEvent.getKeyText 는 AWT 번들(언어)에 따라 달라진다. VK 이름에서 만든다: PAGE_UP → PageUp, F5 → F5
    private static String vkName(int keyCode) {
        String vk = AWTKeyStroke.getAWTKeyStroke(keyCode, 0).toString().replace("pressed ", "");
        StringBuilder name = new StringBuilder();
        for (String word : vk.split("_")) {
            if (!word.isEmpty()) {
                name.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return name.toString();
    }
}
