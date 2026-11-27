package tanager.ui.layout;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tanager.domain.layout.Direction;
import tanager.domain.layout.LayoutGeometry;
import tanager.domain.layout.LayoutNode;
import tanager.domain.layout.Orientation;
import tanager.domain.layout.Rect;
import tanager.domain.session.ShellProfile;
import tanager.domain.shared.PaneId;
import tanager.ui.i18n.Msg;
import tanager.ui.keys.KeyHelp;
import tanager.ui.keys.LayoutAction;

/**
 * 창 머리줄 우클릭 메뉴의 항목(순수, #149, FR-LAY-09 · 10). 분할 ▸ 상하좌우 · 다른 프로파일로 분할 ▸ · 교환 ▸ 방향 · 닫기. "새 탭으로 옮기기"는
 * 탭이 생기는 M5 에서 더한다. 키가 있는 항목은 이름 뒤에 키를 보인다.
 */
public final class PaneMenu {
    private static final List<Direction> DIRECTIONS =
            List.of(Direction.UP, Direction.DOWN, Direction.LEFT, Direction.RIGHT);

    /** 메뉴 한 줄. */
    public sealed interface Entry {}

    /** 누르는 항목. */
    public record Item(String label, boolean enabled, Command command) implements Entry {
        public Item {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(command, "command");
        }
    }

    /** 하위 메뉴. 켜진 항목이 하나도 없으면 비활성이다. */
    public record Submenu(String label, List<Item> items) implements Entry {
        public Submenu {
            Objects.requireNonNull(label, "label");
            items = List.copyOf(items);
        }

        public boolean enabled() {
            return items.stream().anyMatch(Item::enabled);
        }
    }

    /** 항목을 눌렀을 때 할 일. 대상은 메뉴를 연 창이다 — 그 창에 먼저 포커스한다. */
    public sealed interface Command {
        /** 키보드와 같은 동작. */
        record Act(LayoutAction action) implements Command {
            public Act {
                Objects.requireNonNull(action, "action");
            }
        }

        /** 앞쪽(ROW 왼쪽, COLUMN 위)에 새 창을 연다. */
        record SplitBefore(Orientation orientation) implements Command {
            public SplitBefore {
                Objects.requireNonNull(orientation, "orientation");
            }
        }

        /** 오른쪽에 {@code profile} 셸로 새 창을 연다. */
        record SplitWith(ShellProfile profile) implements Command {
            public SplitWith {
                Objects.requireNonNull(profile, "profile");
            }
        }
    }

    private PaneMenu() {}

    /**
     * {@code pane} 의 메뉴. 교환 방향은 {@code area} · {@code gap} 으로 그린 배치에서 그쪽에 이웃이 있을 때만 켠다. 없는 창이면 빈 메뉴다.
     *
     * @param shell 그 창의 셸. 모르면(선택 화면 · 기동 전) 다른 프로파일로 분할을 끈다
     * @param profiles 탐지한 셸(탐지 순서). 그 창의 셸과 같은 환경의 다른 셸이 후보다
     */
    public static List<Entry> entries(
            LayoutNode tree,
            PaneId pane,
            Rect area,
            int gap,
            Optional<ShellProfile> shell,
            List<ShellProfile> profiles) {
        Objects.requireNonNull(tree, "tree");
        Objects.requireNonNull(pane, "pane");
        Objects.requireNonNull(shell, "shell");
        if (!tree.panes().contains(pane)) {
            return List.of();
        }
        Submenu split = new Submenu(
                Msg.PANE_MENU_SPLIT.text(),
                List.of(
                        new Item(Msg.PANE_MENU_UP.text(), true, new Command.SplitBefore(Orientation.COLUMN)),
                        act(Msg.PANE_MENU_DOWN, new LayoutAction.Split(Orientation.COLUMN), true),
                        new Item(Msg.PANE_MENU_LEFT.text(), true, new Command.SplitBefore(Orientation.ROW)),
                        act(Msg.PANE_MENU_RIGHT, new LayoutAction.Split(Orientation.ROW), true)));
        List<Item> others = new ArrayList<>();
        for (ShellProfile other : profiles) {
            if (shell.isPresent()
                    && other.env().equals(shell.get().env())
                    && !other.id().equals(shell.get().id())) {
                others.add(new Item(other.name(), true, new Command.SplitWith(other)));
            }
        }
        List<Item> swaps = new ArrayList<>();
        for (Direction direction : DIRECTIONS) {
            boolean neighbor =
                    LayoutGeometry.neighbor(tree, pane, direction, area, gap).isPresent();
            swaps.add(act(label(direction), new LayoutAction.Swap(direction), neighbor));
        }
        return List.of(
                split,
                new Submenu(Msg.PANE_MENU_SPLIT_WITH.text(), others),
                new Submenu(Msg.PANE_MENU_SWAP.text(), swaps),
                act(Msg.LAYOUT_CLOSE, new LayoutAction.Close(), true));
    }

    private static Item act(Msg name, LayoutAction action, boolean enabled) {
        return new Item(KeyHelp.withKey(name.text(), action), enabled, new Command.Act(action));
    }

    private static Msg label(Direction direction) {
        return switch (direction) {
            case UP -> Msg.PANE_MENU_UP;
            case DOWN -> Msg.PANE_MENU_DOWN;
            case LEFT -> Msg.PANE_MENU_LEFT;
            case RIGHT -> Msg.PANE_MENU_RIGHT;
        };
    }
}
