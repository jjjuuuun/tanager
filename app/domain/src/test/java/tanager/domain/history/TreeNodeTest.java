package tanager.domain.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TreeNodeTest {

    private static final WorkspacePath P =
            WorkspacePath.of(new ShellEnvironment.Wsl("U"), "/a").value().orElseThrow();

    @Test
    @DisplayName("폴더 라벨이 비었거나 공백뿐이면 거부한다")
    void folderRejectsBlankLabel() {
        // when · then
        assertThatThrownBy(() -> new TreeNode.Folder(P, "", Optional.empty(), false, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TreeNode.Folder(P, " ", Optional.empty(), false, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("폴더는 자식 목록을 방어 복사한다")
    void folderCopiesChildren() {
        // given
        List<TreeNode> children = new ArrayList<>();

        // when
        TreeNode.Folder f = new TreeNode.Folder(P, "/a", Optional.empty(), false, children);
        children.add(f);

        // then
        assertThat(f.children()).isEmpty();
    }

    @Test
    @DisplayName("더 보기의 숨긴 수는 1 이상이어야 한다")
    void moreConversationsNeedsHidden() {
        // when · then
        assertThatThrownBy(() -> new TreeNode.MoreConversations(P, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new TreeNode.MoreConversations(P, 1).hidden()).isEqualTo(1);
    }

    @Test
    @DisplayName("트리 조건의 폴더별 대화 수는 음수일 수 없고, 펼친 폴더 목록은 방어 복사한다")
    void treeQueryValidatesConversations() {
        // given
        Set<WorkspacePath> expanded = new java.util.HashSet<>(Set.of(P));

        // when
        TreeQuery q = new TreeQuery("", false, List.of(), 0, expanded);
        expanded.clear();

        // then
        assertThat(q.expandedConversations()).containsExactly(P);
        assertThatThrownBy(() -> new TreeQuery("", false, List.of(), -1, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
