package tanager.domain.history;

import static org.assertj.core.api.Assertions.assertThat;
import static tanager.domain.history.DirectoryTreeTest.env;
import static tanager.domain.history.DirectoryTreeTest.folder;
import static tanager.domain.history.DirectoryTreeTest.history;
import static tanager.domain.history.DirectoryTreeTest.path;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DirectoryTreeMissingTest {
    private static final ShellEnvironment WIN = new ShellEnvironment.Windows();
    private static final TreeQuery ALL = new TreeQuery("", false, List.of(WIN));

    @Test
    @DisplayName("사라진 경로의 방문 폴더에 missing 표시를 붙이고 나머지는 붙이지 않는다")
    void marksMissingVisit() {
        // given
        WorkspacePath gone = path(WIN, "C:\\gone");
        WorkspacePath here = path(WIN, "C:\\here");
        // when
        HistoryTree tree = DirectoryTree.project(history(gone, here), List.of(), ALL, Set.of(gone));
        // then
        List<TreeNode> drive = folder(env(tree, WIN).children().getFirst()).children();
        assertThat(drive).extracting(n -> folder(n).label()).containsExactly("gone", "here");
        assertThat(drive).extracting(n -> folder(n).missing()).containsExactly(true, false);
    }

    @Test
    @DisplayName("Windows 는 대소문자만 다른 표기여도 사라진 경로로 본다")
    void caseInsensitiveOnWindows() {
        // given
        WorkspacePath gone = path(WIN, "C:\\Gone");
        // when
        HistoryTree tree = DirectoryTree.project(history(gone), List.of(), ALL, Set.of(path(WIN, "c:\\gone")));
        // then
        assertThat(folder(env(tree, WIN).children().getFirst()).missing()).isTrue();
    }

    @Test
    @DisplayName("방문 기록이 없는 중간 폴더는 사라진 경로 집합에 있어도 표시하지 않는다")
    void onlyVisitedFolders() {
        // given
        WorkspacePath parent = path(WIN, "C:\\p");
        WorkspacePath a = path(WIN, "C:\\p\\a");
        WorkspacePath b = path(WIN, "C:\\p\\b");
        // when
        HistoryTree tree = DirectoryTree.project(history(a, b), List.of(), ALL, Set.of(parent));
        // then
        TreeNode.Folder p = folder(env(tree, WIN).children().getFirst());
        assertThat(p.visit()).isEmpty();
        assertThat(p.missing()).isFalse();
    }

    @Test
    @DisplayName("고정 항목도 사라진 경로면 표시한다")
    void pinnedMissing() {
        // given
        WorkspacePath gone = path(WIN, "C:\\gone");
        WorkHistory h = history(gone).pin(gone, true).value().orElseThrow();
        // when
        HistoryTree tree = DirectoryTree.project(h, List.of(), ALL, Set.of(gone));
        // then
        assertThat(tree.pinned()).extracting(PinnedEntry::missing).containsExactly(true);
    }

    @Test
    @DisplayName("사라진 경로를 주지 않으면 아무 것도 표시하지 않는다")
    void defaultNoneMissing() {
        // given
        WorkspacePath gone = path(WIN, "C:\\gone");
        WorkHistory h = history(gone).pin(gone, true).value().orElseThrow();
        // when
        HistoryTree tree = DirectoryTree.project(h, List.of(), ALL);
        // then
        assertThat(folder(env(tree, WIN).children().getFirst()).missing()).isFalse();
        assertThat(tree.pinned()).extracting(PinnedEntry::missing).containsExactly(false);
    }
}
