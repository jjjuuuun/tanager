package tanager.ui.sidebar;

import java.util.List;
import java.util.Optional;
import tanager.domain.history.VisitedDirectory;
import tanager.ui.i18n.Msg;

/** 노드 메뉴의 항목과 활성 규칙(순수, #86, SDD §8.4). 창을 열지 않는 동작만 — 열기는 M4(#94). */
final class FolderMenu {
    private FolderMenu() {}

    /** {@code target} 에서 연 메뉴의 항목(위에서 아래로). */
    static List<MenuItem> items(MenuTarget target) {
        return switch (target) {
            case MenuTarget.Folder(var path, Optional<VisitedDirectory> visit) -> {
                boolean visited = visit.isPresent();
                boolean pinned = visit.map(VisitedDirectory::pinned).orElse(false);
                boolean hidden = visit.map(VisitedDirectory::hidden).orElse(false);
                yield List.of(
                        new MenuItem(MenuAction.COPY_PATH, Msg.MENU_COPY_PATH.text(), true),
                        pinned
                                ? new MenuItem(MenuAction.UNPIN, Msg.MENU_UNPIN.text(), visited)
                                : new MenuItem(MenuAction.PIN, Msg.MENU_PIN.text(), visited),
                        new MenuItem(MenuAction.ALIAS, Msg.MENU_ALIAS.text(), visited),
                        hidden
                                ? new MenuItem(MenuAction.UNHIDE, Msg.MENU_UNHIDE.text(), visited)
                                : new MenuItem(MenuAction.HIDE, Msg.MENU_HIDE.text(), visited),
                        new MenuItem(MenuAction.FORGET, Msg.MENU_FORGET.text(), visited));
            }
            case MenuTarget.Environment(var env, boolean showHidden) ->
                List.of(
                        new MenuItem(
                                MenuAction.TOGGLE_HIDDEN,
                                (showHidden ? Msg.MENU_CONCEAL_HIDDEN : Msg.MENU_SHOW_HIDDEN).text(),
                                true),
                        new MenuItem(MenuAction.EXPAND_ALL, Msg.MENU_EXPAND_ALL.text(), true),
                        new MenuItem(MenuAction.COLLAPSE_ALL, Msg.MENU_COLLAPSE_ALL.text(), true));
            case MenuTarget.Session(var pane, var cwd, boolean exited) ->
                List.of(new MenuItem(MenuAction.COPY_PATH, Msg.MENU_COPY_PATH.text(), cwd.isPresent()));
        };
    }

    /** 고정 · 별칭이 있는 항목은 지우기 전에 묻는다 — 사용자가 손을 댄 항목이다. */
    static boolean needsConfirm(VisitedDirectory visit) {
        return visit.pinned() || visit.alias().isPresent();
    }
}
