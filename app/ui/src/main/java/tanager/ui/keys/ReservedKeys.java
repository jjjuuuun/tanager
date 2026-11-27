package tanager.ui.keys;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Set;

/** 재정의에 쓸 수 없는 키 · 경고할 키(SDD §8.2 "셸 · 에디터가 쓰는 키", #183). 레이아웃 키는 터미널보다 먼저 가로채므로 셸이 그 키를 잃는다. */
final class ReservedKeys {
    private static final int CTRL = InputEvent.CTRL_DOWN_MASK;
    private static final int SHIFT = InputEvent.SHIFT_DOWN_MASK;
    private static final int CTRL_SHIFT = CTRL | SHIFT;
    // 셸 · 에디터가 쓰는 Ctrl+글자: 끊기 · 끝 · 멈춤 · 이력 검색 · 화면 지우기 · 줄 처음 · 줄 끝 · 단어 · 줄 지우기
    private static final Set<Integer> SHELL_CTRL_LETTERS = Set.of(
            KeyEvent.VK_C,
            KeyEvent.VK_D,
            KeyEvent.VK_Z,
            KeyEvent.VK_R,
            KeyEvent.VK_L,
            KeyEvent.VK_A,
            KeyEvent.VK_E,
            KeyEvent.VK_W,
            KeyEvent.VK_U,
            KeyEvent.VK_K);
    // 터미널(JediTerm)의 복사 · 붙여넣기 · 스크롤 키(TerminalSettings). 가로채면 그 기능을 잃는다
    private static final Set<Key> TERMINAL_KEYS = Set.of(
            new Key(KeyEvent.VK_C, CTRL_SHIFT),
            new Key(KeyEvent.VK_V, CTRL_SHIFT),
            new Key(KeyEvent.VK_INSERT, CTRL),
            new Key(KeyEvent.VK_PAGE_UP, CTRL_SHIFT),
            new Key(KeyEvent.VK_PAGE_DOWN, CTRL_SHIFT),
            new Key(KeyEvent.VK_UP, CTRL_SHIFT),
            new Key(KeyEvent.VK_DOWN, CTRL_SHIFT));

    private ReservedKeys() {}

    /** 수식키가 없거나 Shift 뿐인 키(글자 · 편집 키, 기능 키 F1~F24 는 예외), Ctrl+셸 글자, 터미널 키. */
    static boolean reserved(Key key) {
        boolean typing = (key.modifiers() & ~SHIFT) == 0;
        if (typing) {
            return !functionKey(key.keyCode());
        }
        return (key.modifiers() == CTRL && SHELL_CTRL_LETTERS.contains(key.keyCode())) || TERMINAL_KEYS.contains(key);
    }

    /** AltGr(Windows 는 Ctrl+Alt 로 온다)로 치는 글자와 겹칠 수 있는 키. 쓸 수는 있다. */
    static boolean altGraphLike(Key key) {
        return key.has(CTRL | InputEvent.ALT_DOWN_MASK) || key.has(InputEvent.ALT_GRAPH_DOWN_MASK);
    }

    private static boolean functionKey(int keyCode) {
        return (keyCode >= KeyEvent.VK_F1 && keyCode <= KeyEvent.VK_F12)
                || (keyCode >= KeyEvent.VK_F13 && keyCode <= KeyEvent.VK_F24);
    }
}
