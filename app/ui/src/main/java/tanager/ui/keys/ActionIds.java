package tanager.ui.keys;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tanager.domain.layout.Direction;
import tanager.domain.layout.Orientation;

/**
 * 설정 파일 {@code keys.bindings} 의 동작 id(SDD §8.2, #183). id 는 바꾸지 않는다 — 바꾸면 사용자의 config.json 이 깨진다. 탭 동작
 * ({@code tab.*})은 탭 막대(#181)가 더한다.
 */
public final class ActionIds {
    private static final Map<String, KeyAction> BY_ID = table();

    private ActionIds() {}

    /** {@code id} 의 동작. 모르는 id(대소문자 포함)면 비어 있다. */
    public static Optional<KeyAction> actionOf(String id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(BY_ID.get(id));
    }

    /** {@code action} 의 id. */
    public static String idOf(KeyAction action) {
        Objects.requireNonNull(action, "action");
        return switch (action) {
            case LayoutAction.Split(Orientation orientation) ->
                orientation == Orientation.ROW ? "pane.splitRight" : "pane.splitDown";
            case LayoutAction.Focus(Direction direction) -> "pane.focus" + suffix(direction);
            case LayoutAction.Resize(Direction direction) -> "pane.resize" + suffix(direction);
            case LayoutAction.Swap(Direction direction) -> "pane.swap" + suffix(direction);
            case LayoutAction.Zoom zoom -> "pane.zoom";
            case LayoutAction.Close close -> "pane.close";
            case KeyAction.ToggleSidebar toggle -> "sidebar.toggle";
            case KeyAction.SearchTree search -> "tree.search";
            case KeyAction.OpenSettings open -> "settings.open";
        };
    }

    private static String suffix(Direction direction) {
        return switch (direction) {
            case LEFT -> "Left";
            case RIGHT -> "Right";
            case UP -> "Up";
            case DOWN -> "Down";
        };
    }

    private static Map<String, KeyAction> table() {
        Map<String, KeyAction> table = new LinkedHashMap<>();
        for (Orientation orientation : Orientation.values()) {
            put(table, new LayoutAction.Split(orientation));
        }
        for (Direction direction : Direction.values()) {
            put(table, new LayoutAction.Focus(direction));
            put(table, new LayoutAction.Resize(direction));
            put(table, new LayoutAction.Swap(direction));
        }
        put(table, new LayoutAction.Zoom());
        put(table, new LayoutAction.Close());
        put(table, new KeyAction.ToggleSidebar());
        put(table, new KeyAction.SearchTree());
        put(table, new KeyAction.OpenSettings());
        return Map.copyOf(table);
    }

    private static void put(Map<String, KeyAction> table, KeyAction action) {
        table.put(idOf(action), action);
    }
}
