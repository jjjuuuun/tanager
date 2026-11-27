package tanager.domain.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
}
