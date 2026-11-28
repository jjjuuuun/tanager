package tanager.ui.sidebar;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tanager.domain.history.EnvironmentNode;
import tanager.domain.history.HistoryTree;
import tanager.domain.history.TreeNode;
import tanager.domain.history.WorkspacePath;

/**
 * 사이드바의 펼침 · 선택(순수 값, #84). 트리를 다시 투영해도 경로 · 창 ID 로 이어 간다.
 *
 * <ul>
 *   <li>폴더는 같은 경로의 노드가 이어받는다. 압축으로 합쳐졌으면 그 경로를 덮는 압축 노드가 이어받는다.
 *   <li>펼친 노드 · 고른 노드가 보이도록 조상을 펼친다. 기억하는 노드는 기억할 때 보이던 것이다 — 압축이 쪼개져 새 부모가 생겨도 보인다.
 *   <li>사라진 노드의 기억은 버린다.
 * </ul>
 *
 * @param expanded 펼친 노드
 * @param selected 고른 노드
 */
record ExpansionState(Set<NodeKey> expanded, Optional<NodeKey> selected) {

    ExpansionState {
        expanded = Set.copyOf(expanded);
        Objects.requireNonNull(selected, "selected");
    }

    static ExpansionState empty() {
        return new ExpansionState(Set.of(), Optional.empty());
    }

    /** 새 트리에서 펼칠 노드와 고를 노드(모두 새 트리의 키). */
    record Restored(Set<NodeKey> expanded, Optional<NodeKey> selected) {
        Restored {
            expanded = Set.copyOf(expanded);
            Objects.requireNonNull(selected, "selected");
        }
    }

    /** {@code tree} 에 이 상태를 옮긴다. */
    Restored restoreIn(HistoryTree tree) {
        Set<NodeKey> open = new HashSet<>();
        Chosen chosen = new Chosen();
        NodeKey pinnedSection = new NodeKey.PinnedSection();
        if (!tree.pinned().isEmpty()) {
            boolean pinnedChosen = false;
            for (var pinned : tree.pinned()) {
                NodeKey key = new NodeKey.Pinned(pinned.entry().path());
                if (selected.equals(Optional.of(key))) {
                    chosen.value = Optional.of(key);
                    pinnedChosen = true;
                }
            }
            if (expanded.contains(pinnedSection) || pinnedChosen) {
                open.add(pinnedSection);
            }
            if (selected.equals(Optional.of(pinnedSection))) {
                chosen.value = Optional.of(pinnedSection);
            }
        }
        for (EnvironmentNode env : tree.environments()) {
            NodeKey key = new NodeKey.Environment(env.env());
            boolean reveal = walk(env.children(), Optional.empty(), open, chosen);
            if (expanded.contains(key) || reveal) {
                open.add(key);
            }
            if (selected.equals(Optional.of(key))) {
                chosen.value = Optional.of(key);
            }
        }
        return new Restored(open, chosen.value);
    }

    /** @return 이 노드들 중 펼치거나 고른 것이 있어 부모가 펼쳐져야 하는지 */
    private boolean walk(List<TreeNode> nodes, Optional<WorkspacePath> parent, Set<NodeKey> open, Chosen chosen) {
        boolean reveal = false;
        for (TreeNode node : nodes) {
            switch (node) {
                case TreeNode.Session session -> {
                    NodeKey key = Nodes.keyOf(session);
                    if (selected.equals(Optional.of(key))) {
                        chosen.value = Optional.of(key);
                        reveal = true;
                    }
                }
                case TreeNode.Folder folder -> {
                    NodeKey key = Nodes.keyOf(folder);
                    boolean childReveal = walk(folder.children(), Optional.of(folder.path()), open, chosen);
                    boolean remembered = expanded.stream().anyMatch(k -> covers(folder, parent, k));
                    if (remembered || childReveal) {
                        open.add(key);
                    }
                    if (selected.filter(k -> covers(folder, parent, k)).isPresent()) {
                        chosen.value = Optional.of(key);
                        reveal = true;
                    }
                    reveal |= remembered || childReveal;
                }
                // 아직 그리지 않는 노드라 펼침 · 선택이 없다(#203).
                case TreeNode.Conversation _, TreeNode.MoreConversations _ -> {}
            }
        }
        return reveal;
    }

    /** {@code folder} 가 기억한 {@code key} 를 이어받는가: 같은 경로이거나, 압축 노드가 그 경로를 덮는다(부모 아래 · 자기 경로 위). */
    private static boolean covers(TreeNode.Folder folder, Optional<WorkspacePath> parent, NodeKey key) {
        if (!(key instanceof NodeKey.Folder(WorkspacePath remembered))) {
            return false;
        }
        if (remembered.equals(folder.path())) {
            return true;
        }
        return isStrictAncestor(remembered, folder.path())
                && parent.map(p -> isStrictAncestor(p, remembered)).orElse(true);
    }

    private static boolean isStrictAncestor(WorkspacePath ancestor, WorkspacePath path) {
        Optional<WorkspacePath> up = path.parent();
        while (up.isPresent()) {
            if (up.get().equals(ancestor)) {
                return true;
            }
            up = up.get().parent();
        }
        return false;
    }

    /** 한 번의 {@link #restoreIn} 안에서만 쓰는 선택 결과. */
    private static final class Chosen {
        private Optional<NodeKey> value = Optional.empty();
    }
}
