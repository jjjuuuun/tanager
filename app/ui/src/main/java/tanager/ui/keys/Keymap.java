package tanager.ui.keys;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import tanager.application.port.out.KeyConfig;
import tanager.domain.layout.Direction;
import tanager.domain.layout.Orientation;

/**
 * 키 표(SDD §8.2). 불변 값이다 — 사용자 재정의는 {@link #resolve} 가 새 표로 돌려준다(FR-CFG-01, #183). 이 키들은 터미널보다 먼저
 * 가로채므로 셸(readline 의 Alt+← 등)에는 가지 않는다.
 */
public final class Keymap {
    private static final int ALT = InputEvent.ALT_DOWN_MASK;
    private static final int CTRL = InputEvent.CTRL_DOWN_MASK;
    private static final int SHIFT = InputEvent.SHIFT_DOWN_MASK;
    private static final int ALT_SHIFT = ALT | SHIFT;
    private static final int CTRL_SHIFT = CTRL | SHIFT;
    // 셸 · 에디터가 쓰는 Ctrl+글자(SDD §8.2): 끊기 · 끝 · 멈춤 · 이력 검색 · 화면 지우기 · 줄 처음 · 줄 끝 · 단어 · 줄 지우기
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

    /**
     * 키 하나와 그 이름. 이름은 툴팁에 보인다. 이름이 같은 바인딩은 한 키다(자판 · 숫자패드에 따라 키 코드만 다른 =, -).
     *
     * @param modifiers {@link InputEvent#getModifiersEx()} 의 키보드 수식키
     */
    public record Binding(int keyCode, int modifiers, String text, KeyAction action) {
        public Binding {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(action, "action");
        }

        Binding(Key key, KeyAction action) {
            this(key.keyCode(), key.modifiers(), key.text(), action);
        }

        Key key() {
            return new Key(keyCode, modifiers);
        }
    }

    // 같은 동작의 키가 여럿이면 처음 것이 툴팁에 보인다. = 와 - 는 자판 · 숫자패드에 따라 키 코드가 다르다
    private static final Keymap DEFAULTS = new Keymap(List.of(
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
            new Binding(KeyEvent.VK_RIGHT, CTRL_SHIFT, "Ctrl+Shift+→", new LayoutAction.Swap(Direction.RIGHT)),
            new Binding(KeyEvent.VK_E, CTRL_SHIFT, "Ctrl+Shift+E", new KeyAction.ToggleSidebar()),
            new Binding(KeyEvent.VK_F, CTRL_SHIFT, "Ctrl+Shift+F", new KeyAction.SearchTree()),
            new Binding(KeyEvent.VK_COMMA, CTRL, "Ctrl+,", new KeyAction.OpenSettings())));

    private final List<Binding> bindings;

    private Keymap(List<Binding> bindings) {
        this.bindings = List.copyOf(bindings);
    }

    /** SDD §8.2 의 기본 키. */
    public static Keymap defaults() {
        return DEFAULTS;
    }

    /** 표의 바인딩(툴팁 순서). */
    public List<Binding> bindings() {
        return bindings;
    }

    /**
     * 눌린 키의 동작. 수식키는 정확히 같아야 한다 — AltGr(Windows 는 Ctrl+Alt)로 치는 글자나 수식키가 더 붙은 키는 비어 셸로 간다.
     *
     * @param modifiersEx {@link InputEvent#getModifiersEx()}. 마우스 버튼 비트는 무시한다
     */
    public Optional<KeyAction> actionFor(int keyCode, int modifiersEx) {
        int modifiers = modifiersEx & Key.KEYBOARD_MODIFIERS;
        for (Binding binding : bindings) {
            if (binding.keyCode() == keyCode && binding.modifiers() == modifiers) {
                return Optional.of(binding.action());
            }
        }
        return Optional.empty();
    }

    /** {@code action} 의 키. 키가 없는 동작(위아래 교환 등 메뉴 전용, 재정의에 키를 잃은 동작)이면 비어 있다. */
    public Optional<Binding> bindingOf(KeyAction action) {
        Objects.requireNonNull(action, "action");
        for (Binding binding : bindings) {
            if (binding.action().equals(action)) {
                return Optional.of(binding);
            }
        }
        return Optional.empty();
    }

    /** 툴팁에 보일 {@code action} 의 키 이름(예 {@code Alt+Shift+=}). */
    public Optional<String> keyOf(KeyAction action) {
        return bindingOf(action).map(Binding::text);
    }

    /**
     * {@code defaults} 위에 {@code config} 의 재정의를 얹는다(#183). 예외를 던지지 않는다 — 문제는 목록으로 돌려준다.
     *
     * <ul>
     *   <li>재정의가 기본 키를 이긴다. 키를 잃은 동작은 그 키가 없어지고 {@link KeyProblem.Unbound} 다. 그래서 두 동작의 키를 맞바꿀 수 있다.
     *   <li>재정의끼리 같은 키면 모두 버리고 기본 키로 돌린 뒤 다시 검사한다({@link KeyProblem.Conflict}).
     *   <li>읽지 못한 키 · 예약 키는 그 동작만 기본 키다. 모르는 동작은 무시한다.
     * </ul>
     *
     * 문제는 prefix → 동작 id 순서의 한 줄 문제 → 겹침 → 키를 잃은 동작 순서다.
     */
    public static KeymapResolution resolve(Keymap defaults, KeyConfig config) {
        Objects.requireNonNull(defaults, "defaults");
        Objects.requireNonNull(config, "config");
        List<KeyProblem> problems = new ArrayList<>();
        config.prefix().ifPresent(raw -> problems.add(new KeyProblem.PrefixNotSupported(raw)));
        Map<KeyAction, Key> overrides = parsed(config.bindings(), problems);
        dropConflicts(overrides, problems);

        Map<Key, KeyAction> claimed = new LinkedHashMap<>();
        overrides.forEach((action, key) -> claimed.put(key, action));
        // 동작 + 키 이름 → 가져간 동작. 이름이 같은 바인딩(= 의 키 코드 여럿)은 한 키라 함께 잃는다
        Map<NamedKey, KeyAction> lost = new LinkedHashMap<>();
        for (Binding binding : defaults.bindings) {
            KeyAction by = claimed.get(binding.key());
            if (by != null && !overrides.containsKey(binding.action())) {
                lost.putIfAbsent(new NamedKey(binding.action(), binding.text()), by);
            }
        }

        List<Binding> result = new ArrayList<>();
        Set<KeyAction> placed = new HashSet<>();
        for (Binding binding : defaults.bindings) {
            KeyAction action = binding.action();
            if (overrides.containsKey(action)) {
                if (placed.add(action)) {
                    result.add(new Binding(overrides.get(action), action));
                }
            } else if (!lost.containsKey(new NamedKey(action, binding.text()))) {
                result.add(binding);
            }
        }
        overrides.forEach((action, key) -> {
            if (placed.add(action)) {
                result.add(new Binding(key, action));
            }
        });
        lost.forEach((named, by) ->
                problems.add(new KeyProblem.Unbound(ActionIds.idOf(named.action()), named.text(), ActionIds.idOf(by))));
        return new KeymapResolution(new Keymap(result), problems);
    }

    /** 동작의 키 하나(이름 기준 — 키 코드만 다른 바인딩은 같은 키다). */
    private record NamedKey(KeyAction action, String text) {}

    // 동작 id 순서로 읽는다 — 문제 순서가 설정 파일의 키 순서(Map)에 따라 바뀌지 않게
    private static Map<KeyAction, Key> parsed(Map<String, String> bindings, List<KeyProblem> problems) {
        Map<KeyAction, Key> overrides = new LinkedHashMap<>();
        for (String id : new TreeSet<>(bindings.keySet())) {
            String raw = bindings.get(id);
            Optional<KeyAction> action = ActionIds.actionOf(id);
            if (action.isEmpty()) {
                problems.add(new KeyProblem.UnknownAction(id));
                continue;
            }
            Optional<Key> key = Key.parse(raw);
            if (key.isEmpty()) {
                problems.add(new KeyProblem.Unparseable(id, raw));
                continue;
            }
            if (reserved(key.get())) {
                problems.add(new KeyProblem.Reserved(id, key.get().text()));
                continue;
            }
            if (key.get().has(CTRL | ALT) || key.get().has(InputEvent.ALT_GRAPH_DOWN_MASK)) {
                problems.add(new KeyProblem.AltGraph(id, key.get().text()));
            }
            overrides.put(action.get(), key.get());
        }
        return overrides;
    }

    private static void dropConflicts(Map<KeyAction, Key> overrides, List<KeyProblem> problems) {
        Map<Key, List<KeyAction>> byKey = new LinkedHashMap<>();
        overrides.forEach((action, key) ->
                byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(action));
        for (Map.Entry<Key, List<KeyAction>> entry : byKey.entrySet()) {
            if (entry.getValue().size() > 1) {
                List<String> ids = new ArrayList<>();
                for (KeyAction action : entry.getValue()) {
                    ids.add(ActionIds.idOf(action));
                    overrides.remove(action);
                }
                problems.add(new KeyProblem.Conflict(entry.getKey().text(), ids));
            }
        }
    }

    // 수식키가 없거나 Shift 뿐인 키는 글자 · 편집 키다(기능 키 F1~F24 는 예외). Ctrl+셸 글자와 터미널 키도 막는다
    private static boolean reserved(Key key) {
        boolean typing = (key.modifiers() & ~SHIFT) == 0;
        if (typing) {
            return !functionKey(key.keyCode());
        }
        return (key.modifiers() == CTRL && SHELL_CTRL_LETTERS.contains(key.keyCode())) || TERMINAL_KEYS.contains(key);
    }

    private static boolean functionKey(int keyCode) {
        return (keyCode >= KeyEvent.VK_F1 && keyCode <= KeyEvent.VK_F12)
                || (keyCode >= KeyEvent.VK_F13 && keyCode <= KeyEvent.VK_F24);
    }
}
