package tanager.ui.keys;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import tanager.domain.layout.Direction;
import tanager.domain.layout.Orientation;
import tanager.ui.i18n.Msg;

/** 레이아웃 키 도움말 · 툴팁 글자(#148). 키 이름은 {@link Keymap} 에서 가져와 표와 어긋나지 않는다. */
public final class KeyHelp {
    private static final List<Direction> ARROWS =
            List.of(Direction.LEFT, Direction.UP, Direction.RIGHT, Direction.DOWN);

    /** 도움말 한 줄: 동작 이름과 키. */
    public record Row(String action, String keys) {
        public Row {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(keys, "keys");
        }
    }

    private KeyHelp() {}

    /** 표(SDD §8.2)의 동작마다 한 줄. 방향만 다른 키는 화살표를 이어 한 줄로 보인다. */
    public static List<Row> rows() {
        return List.of(
                new Row(Msg.LAYOUT_SPLIT_RIGHT.text(), key(new LayoutAction.Split(Orientation.ROW))),
                new Row(Msg.LAYOUT_SPLIT_DOWN.text(), key(new LayoutAction.Split(Orientation.COLUMN))),
                new Row(Msg.LAYOUT_FOCUS.text(), arrows(LayoutAction.Focus::new, ARROWS)),
                new Row(Msg.LAYOUT_RESIZE.text(), arrows(LayoutAction.Resize::new, ARROWS)),
                new Row(Msg.LAYOUT_ZOOM.text(), key(new LayoutAction.Zoom())),
                new Row(Msg.LAYOUT_CLOSE.text(), key(new LayoutAction.Close())),
                new Row(
                        Msg.LAYOUT_SWAP.text(),
                        arrows(LayoutAction.Swap::new, List.of(Direction.LEFT, Direction.RIGHT))));
    }

    /** 버튼 · 메뉴 툴팁: {@code name} 에 키를 괄호로 붙인다. 키가 없으면 이름만. */
    public static String withKey(String name, LayoutAction action) {
        Objects.requireNonNull(name, "name");
        return Keymap.keyOf(action)
                .map(key -> Msg.LAYOUT_WITH_KEY.text(name, key))
                .orElse(name);
    }

    private static String key(LayoutAction action) {
        return Keymap.keyOf(action).orElseThrow(() -> new IllegalStateException("키가 없는 동작: " + action));
    }

    // "Alt+←" · "Alt+↑" … → "Alt+←↑→↓": 마지막 + 앞은 같고 뒤의 화살표만 다르다
    private static String arrows(Function<Direction, LayoutAction> action, List<Direction> directions) {
        String first = key(action.apply(directions.getFirst()));
        StringBuilder text = new StringBuilder(first.substring(0, first.lastIndexOf('+') + 1));
        for (Direction direction : directions) {
            String each = key(action.apply(direction));
            text.append(each.substring(each.lastIndexOf('+') + 1));
        }
        return text.toString();
    }
}
