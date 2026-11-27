package tanager.ui.sidebar;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tanager.application.port.in.OpenChoices;
import tanager.application.port.in.OpenError;
import tanager.domain.session.ShellProfile;
import tanager.ui.i18n.Msg;
import tanager.ui.i18n.NoticeText;

/** 노드의 열기 메뉴 항목(순수, #91 · #92, SDD §8.4). 기존 메뉴(경로 복사 · 고정 …) 위에 놓인다. */
final class OpenMenu {
    private OpenMenu() {}

    /** {@code target} 에서 연 메뉴의 열기 항목. {@code choices} 는 폴더일 때 application 이 계산한 후보다. */
    static List<MenuEntry> entries(MenuTarget target, OpenChoices choices) {
        return switch (target) {
            case MenuTarget.Folder folder -> {
                Optional<String> noShell = choices.primary().isPresent()
                        ? Optional.empty()
                        : Optional.of(NoticeText.DEFAULT.of(new OpenError.NoProfileForEnvironment(
                                folder.path().env())));
                Submenu here = new Submenu(
                        Msg.MENU_OPEN_HERE.text(),
                        List.of(
                                new MenuItem(MenuAction.OPEN_RIGHT, Msg.MENU_OPEN_RIGHT.text(), noShell.isEmpty()),
                                new MenuItem(MenuAction.OPEN_DOWN, Msg.MENU_OPEN_DOWN.text(), noShell.isEmpty())),
                        noShell);
                List<MenuItem> others = new ArrayList<>();
                for (ShellProfile shell : choices.others()) {
                    others.add(new MenuItem(MenuAction.OPEN_WITH, shell.name(), true, Optional.of(shell.id())));
                }
                Optional<String> noOther = noShell.or(
                        () -> others.isEmpty() ? Optional.of(Msg.MENU_NO_OTHER_PROFILE.text()) : Optional.empty());
                yield List.of(here, new Submenu(Msg.MENU_OPEN_WITH.text(), others, noOther));
            }
            case MenuTarget.Environment _ -> List.of(new MenuItem(MenuAction.NEW_PANE, Msg.MENU_NEW_PANE.text(), true));
            case MenuTarget.Session session ->
                List.of(
                        new MenuItem(MenuAction.FOCUS, Msg.MENU_FOCUS.text(), true),
                        // 경로를 모르는 세션(홈)도 연다 — 그 프로파일의 시작 경로(→ 홈)에서 뜬다
                        new Submenu(
                                Msg.MENU_OPEN_SAME.text(),
                                List.of(
                                        new MenuItem(MenuAction.OPEN_RIGHT, Msg.MENU_OPEN_RIGHT.text(), true),
                                        new MenuItem(MenuAction.OPEN_DOWN, Msg.MENU_OPEN_DOWN.text(), true)),
                                Optional.empty()),
                        new MenuItem(MenuAction.RESTART, Msg.MENU_RESTART.text(), session.exited()),
                        new MenuItem(MenuAction.CLOSE, Msg.MENU_CLOSE.text(), true));
        };
    }
}
