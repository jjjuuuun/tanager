package tanager.ui.sidebar;

import java.util.List;
import java.util.Optional;
import tanager.domain.history.VisitedDirectory;

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
                        new MenuItem(MenuAction.COPY_PATH, "경로 복사", true),
                        pinned
                                ? new MenuItem(MenuAction.UNPIN, "고정 해제", visited)
                                : new MenuItem(MenuAction.PIN, "고정", visited),
                        new MenuItem(MenuAction.ALIAS, "별칭…", visited),
                        hidden
                                ? new MenuItem(MenuAction.UNHIDE, "숨김 해제", visited)
                                : new MenuItem(MenuAction.HIDE, "숨기기", visited),
                        new MenuItem(MenuAction.FORGET, "기록 삭제", visited));
            }
            case MenuTarget.Environment(var env, boolean showHidden) ->
                List.of(
                        new MenuItem(MenuAction.TOGGLE_HIDDEN, showHidden ? "숨긴 폴더 감추기" : "숨긴 폴더 보기", true),
                        new MenuItem(MenuAction.EXPAND_ALL, "모두 펼치기", true),
                        new MenuItem(MenuAction.COLLAPSE_ALL, "모두 접기", true));
            case MenuTarget.Session(var cwd) -> List.of(new MenuItem(MenuAction.COPY_PATH, "경로 복사", cwd.isPresent()));
        };
    }

    /** 고정 · 별칭이 있는 항목은 지우기 전에 묻는다 — 사용자가 손을 댄 항목이다. */
    static boolean needsConfirm(VisitedDirectory visit) {
        return visit.pinned() || visit.alias().isPresent();
    }
}
