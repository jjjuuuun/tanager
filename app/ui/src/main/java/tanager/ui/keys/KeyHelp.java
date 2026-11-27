package tanager.ui.keys;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import tanager.domain.layout.Direction;
import tanager.domain.layout.Orientation;
import tanager.ui.i18n.Msg;

/** 키 도움말 · 툴팁 글자(#148). 키 이름은 지금 쓰는 {@link Keymap} 에서 가져와 실제 키와 어긋나지 않는다(#183). */
public final class KeyHelp {
    private static final List<Direction> ARROWS =
            List.of(Direction.LEFT, Direction.UP, Direction.RIGHT, Direction.DOWN);
    private static final String NO_KEY = "—";
    private static final String ARROW_GLYPHS = "←↑→↓";

    /** 도움말 한 줄: 동작 이름과 키. */
    public record Row(String action, String keys) {
        public Row {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(keys, "keys");
        }
    }

    private KeyHelp() {}

    /** 레이아웃 표(SDD §8.2)의 동작마다 한 줄. 방향만 다른 키는 화살표를 이어 한 줄로 보인다. 키가 없는 동작은 —. */
    public static List<Row> rows(Keymap keymap) {
        Objects.requireNonNull(keymap, "keymap");
        return List.of(
                new Row(Msg.LAYOUT_SPLIT_RIGHT.text(), key(keymap, new LayoutAction.Split(Orientation.ROW))),
                new Row(Msg.LAYOUT_SPLIT_DOWN.text(), key(keymap, new LayoutAction.Split(Orientation.COLUMN))),
                new Row(Msg.LAYOUT_FOCUS.text(), arrows(keymap, LayoutAction.Focus::new, ARROWS)),
                new Row(Msg.LAYOUT_RESIZE.text(), arrows(keymap, LayoutAction.Resize::new, ARROWS)),
                new Row(Msg.LAYOUT_ZOOM.text(), key(keymap, new LayoutAction.Zoom())),
                new Row(Msg.LAYOUT_CLOSE.text(), key(keymap, new LayoutAction.Close())),
                new Row(
                        Msg.LAYOUT_SWAP.text(),
                        arrows(keymap, LayoutAction.Swap::new, List.of(Direction.LEFT, Direction.RIGHT))));
    }

    /** 레이아웃 밖의 키(사이드바 · 트리 검색 · 설정 열기)마다 한 줄. */
    public static List<Row> appRows(Keymap keymap) {
        Objects.requireNonNull(keymap, "keymap");
        return List.of(
                new Row(Msg.KEYS_SIDEBAR_TOGGLE.text(), key(keymap, new KeyAction.ToggleSidebar())),
                new Row(Msg.KEYS_TREE_SEARCH.text(), key(keymap, new KeyAction.SearchTree())),
                new Row(Msg.SETTINGS_OPEN.text(), key(keymap, new KeyAction.OpenSettings())));
    }

    /** 버튼 · 메뉴 툴팁: {@code name} 에 키를 괄호로 붙인다. 키가 없으면 이름만. */
    public static String withKey(Keymap keymap, String name, KeyAction action) {
        Objects.requireNonNull(keymap, "keymap");
        Objects.requireNonNull(name, "name");
        return keymap.keyOf(action)
                .map(key -> Msg.LAYOUT_WITH_KEY.text(name, key))
                .orElse(name);
    }

    private static String key(Keymap keymap, KeyAction action) {
        return keymap.keyOf(action).orElse(NO_KEY);
    }

    // "Alt+←" · "Alt+↑" … → "Alt+←↑→↓": 모든 방향에 키가 있고 마지막 + 앞이 같으면 뒤의 화살표만 잇는다. 아니면 " · " 로 나열한다
    private static String arrows(Keymap keymap, Function<Direction, LayoutAction> action, List<Direction> directions) {
        List<String> keys = new ArrayList<>();
        for (Direction direction : directions) {
            keymap.keyOf(action.apply(direction)).ifPresent(keys::add);
        }
        if (keys.isEmpty()) {
            return NO_KEY;
        }
        Optional<String> prefix = commonPrefix(keys);
        if (keys.size() != directions.size() || prefix.isEmpty()) {
            return String.join(" · ", keys);
        }
        StringBuilder text = new StringBuilder(prefix.get());
        for (String each : keys) {
            text.append(each.substring(prefix.get().length()));
        }
        return text.toString();
    }

    private static Optional<String> commonPrefix(List<String> keys) {
        String first = keys.getFirst();
        String prefix = first.substring(0, first.lastIndexOf('+') + 1);
        for (String each : keys) {
            int split = each.lastIndexOf('+') + 1;
            String rest = each.substring(split);
            if (!each.substring(0, split).equals(prefix)
                    || rest.length() != 1
                    || ARROW_GLYPHS.indexOf(rest.charAt(0)) < 0) {
                return Optional.empty();
            }
        }
        return Optional.of(prefix);
    }
}
