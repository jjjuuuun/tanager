package tanager.ui.sidebar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.domain.history.Alias;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.VisitedDirectory;
import tanager.domain.history.WorkspacePath;

/** 폴더 · 환경 · 세션 메뉴의 항목과 활성 규칙(순수, #86, FR-HIS-04 · 06). */
class FolderMenuTest {
    private static final ShellEnvironment WIN = new ShellEnvironment.Windows();
    private static final WorkspacePath INFRA =
            WorkspacePath.of(WIN, "C:\\work\\infra").value().orElseThrow();
    private static final Instant T0 = Instant.parse("2026-11-27T00:00:00Z");

    private static VisitedDirectory visit(boolean pinned, Optional<String> alias, boolean hidden) {
        return new VisitedDirectory(INFRA, T0, T0, 1, pinned, alias.map(Alias::new), hidden);
    }

    private static List<MenuAction> enabled(List<MenuItem> items) {
        return items.stream().filter(MenuItem::enabled).map(MenuItem::action).toList();
    }

    @Test
    @DisplayName("방문한 폴더는 경로 복사 · 고정 · 별칭 · 숨기기 · 기록 삭제 다섯 항목이 모두 활성이다")
    void visitedFolderHasFiveEnabledItems() {
        // when
        List<MenuItem> items =
                FolderMenu.items(new MenuTarget.Folder(INFRA, Optional.of(visit(false, Optional.empty(), false))));

        // then
        assertThat(enabled(items))
                .containsExactly(
                        MenuAction.COPY_PATH, MenuAction.PIN, MenuAction.ALIAS, MenuAction.HIDE, MenuAction.FORGET);
        assertThat(items).extracting(MenuItem::label).containsExactly("경로 복사", "고정", "별칭…", "숨기기", "기록 삭제");
    }

    @Test
    @DisplayName("고정한 항목은 고정 해제, 숨긴 항목은 숨김 해제로 바뀐다(고정 항목은 숨길 수 없다)")
    void pinnedOrHiddenFolderOffersUndo() {
        // when
        List<MenuItem> pinned =
                FolderMenu.items(new MenuTarget.Folder(INFRA, Optional.of(visit(true, Optional.empty(), false))));
        List<MenuItem> hidden =
                FolderMenu.items(new MenuTarget.Folder(INFRA, Optional.of(visit(false, Optional.empty(), true))));

        // then
        assertThat(enabled(pinned)).contains(MenuAction.UNPIN).doesNotContain(MenuAction.PIN);
        assertThat(enabled(hidden)).contains(MenuAction.UNHIDE).doesNotContain(MenuAction.HIDE);
        assertThat(pinned).extracting(MenuItem::label).contains("고정 해제");
        assertThat(hidden).extracting(MenuItem::label).contains("숨김 해제");
    }

    @Test
    @DisplayName("방문 기록이 없는 노드(압축 중간 · 세션만 있는 폴더)는 경로 복사만 활성이다")
    void unvisitedFolderOnlyCopies() {
        // when
        List<MenuItem> items = FolderMenu.items(new MenuTarget.Folder(INFRA, Optional.empty()));

        // then
        assertThat(enabled(items)).containsExactly(MenuAction.COPY_PATH);
        assertThat(items).hasSize(5);
    }

    @Test
    @DisplayName("환경 노드 메뉴는 숨긴 폴더 보기 토글 · 모두 펼치기 · 접기다")
    void environmentMenu() {
        // when
        List<MenuItem> off = FolderMenu.items(new MenuTarget.Environment(WIN, false));
        List<MenuItem> on = FolderMenu.items(new MenuTarget.Environment(WIN, true));

        // then
        assertThat(off).extracting(MenuItem::label).containsExactly("숨긴 폴더 보기", "모두 펼치기", "모두 접기");
        assertThat(on).extracting(MenuItem::label).containsExactly("숨긴 폴더 감추기", "모두 펼치기", "모두 접기");
        assertThat(enabled(off))
                .containsExactly(MenuAction.TOGGLE_HIDDEN, MenuAction.EXPAND_ALL, MenuAction.COLLAPSE_ALL);
    }

    @Test
    @DisplayName("세션 메뉴는 경로를 알 때만 경로 복사가 활성이다")
    void sessionMenu() {
        assertThat(enabled(FolderMenu.items(
                        new MenuTarget.Session(new tanager.domain.shared.PaneId("p1"), Optional.of(INFRA), false))))
                .containsExactly(MenuAction.COPY_PATH);
        assertThat(enabled(FolderMenu.items(
                        new MenuTarget.Session(new tanager.domain.shared.PaneId("p1"), Optional.empty(), true))))
                .isEmpty();
    }

    @Test
    @DisplayName("고정 · 별칭이 있는 항목의 기록 삭제만 확인을 묻는다")
    void forgetAsksOnlyForPinnedOrAliased() {
        assertThat(FolderMenu.needsConfirm(visit(true, Optional.empty(), false)))
                .isTrue();
        assertThat(FolderMenu.needsConfirm(visit(false, Optional.of("인프라"), false)))
                .isTrue();
        assertThat(FolderMenu.needsConfirm(visit(false, Optional.empty(), false)))
                .isFalse();
    }
}
