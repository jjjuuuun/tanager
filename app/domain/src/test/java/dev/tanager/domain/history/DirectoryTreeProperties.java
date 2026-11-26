package dev.tanager.domain.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class DirectoryTreeProperties {

    private static final ShellEnvironment WIN = new ShellEnvironment.Windows();
    private static final ShellEnvironment WSL = new ShellEnvironment.Wsl("U");
    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

    @Provide
    Arbitrary<List<WorkspacePath>> paths() {
        Arbitrary<String> seg = Arbitraries.of("a", "B", "b", "src", "Src", "x y", "proj");
        Arbitrary<String> wsl = seg.list().ofMinSize(0).ofMaxSize(4).map(l -> "/" + String.join("/", l));
        Arbitrary<String> win = seg.list()
                .ofMinSize(0)
                .ofMaxSize(4)
                .flatMap(l -> Arbitraries.of("C:\\", "d:\\").map(root -> root + String.join("\\", l)));
        Arbitrary<WorkspacePath> wslPath =
                wsl.map(s -> WorkspacePath.of(WSL, s).value().orElseThrow());
        Arbitrary<WorkspacePath> winPath =
                win.map(s -> WorkspacePath.of(WIN, s).value().orElseThrow());
        return Arbitraries.oneOf(wslPath, winPath).list().ofMaxSize(12);
    }

    private static HistoryTree project(List<WorkspacePath> visited) {
        WorkHistory h = WorkHistory.empty(HistoryPolicy.DEFAULT);
        for (WorkspacePath p : visited) {
            h = h.recordVisit(p, T);
        }
        return DirectoryTree.project(h, List.of(), new TreeQuery("", false, List.of(WSL, WIN)));
    }

    private static void collect(List<TreeNode> nodes, List<TreeNode.Folder> out) {
        for (TreeNode n : nodes) {
            if (n instanceof TreeNode.Folder f) {
                out.add(f);
                collect(f.children(), out);
            }
        }
    }

    private static List<TreeNode.Folder> folders(HistoryTree tree) {
        List<TreeNode.Folder> out = new ArrayList<>();
        for (EnvironmentNode e : tree.environments()) {
            collect(e.children(), out);
        }
        return out;
    }

    @Property
    @Label("투영의 모든 방문 경로는 트리에 방문 노드로 정확히 한 번 나타난다")
    void everyVisitAppearsExactlyOnce(@ForAll("paths") List<WorkspacePath> visited) {
        List<WorkspacePath> shown = folders(project(visited)).stream()
                .filter(f -> f.visit().isPresent())
                .map(f -> f.visit().orElseThrow().path())
                .toList();

        Set<WorkspacePath> expected = new HashSet<>(visited);
        assertThat(shown).hasSize(expected.size()).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Property
    @Label("압축 뒤 방문하지 않은 중간 폴더는 자식이 둘 이상이거나 루트다")
    void unvisitedInnerNodesBranchOrAreRoots(@ForAll("paths") List<WorkspacePath> visited) {
        for (TreeNode.Folder f : folders(project(visited))) {
            boolean isRoot = f.path().parent().isEmpty();
            if (f.visit().isEmpty() && !isRoot) {
                assertThat(f.children()).as("압축되지 않은 중간 노드 %s", f.label()).hasSizeGreaterThan(1);
            }
            if (f.visit().isEmpty() && isRoot) {
                assertThat(f.children()).isNotEmpty();
            }
        }
    }

    @Property
    @Label("모든 폴더의 자식 경로는 그 폴더 경로 아래에 있고 라벨은 비지 않는다")
    void childrenLieUnderParent(@ForAll("paths") List<WorkspacePath> visited) {
        for (TreeNode.Folder f : folders(project(visited))) {
            assertThat(f.label()).isNotBlank();
            for (TreeNode c : f.children()) {
                if (c instanceof TreeNode.Folder child) {
                    assertThat(isUnder(child.path(), f.path())).isTrue();
                }
            }
        }
    }

    @Property
    @Label("같은 입력의 투영은 방문 순서와 상관없이 같은 트리다")
    void projectionIgnoresVisitOrder(@ForAll("paths") List<WorkspacePath> visited) {
        List<WorkspacePath> reversed = new ArrayList<>(visited);
        java.util.Collections.reverse(reversed);

        assertThat(shape(project(reversed))).isEqualTo(shape(project(visited)));
    }

    private static List<String> shape(HistoryTree tree) {
        return folders(tree).stream()
                .map(f -> (f.label() + "|" + f.path().path() + "|" + f.visit().isPresent() + "|"
                                + f.children().size())
                        .toLowerCase(java.util.Locale.ROOT))
                .toList();
    }

    private static boolean isUnder(WorkspacePath child, WorkspacePath ancestor) {
        var p = child.parent();
        while (p.isPresent()) {
            if (p.get().equals(ancestor)) {
                return true;
            }
            p = p.get().parent();
        }
        return false;
    }
}
