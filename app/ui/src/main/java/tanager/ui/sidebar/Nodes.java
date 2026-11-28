package tanager.ui.sidebar;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tanager.domain.history.EnvironmentNode;
import tanager.domain.history.HistoryTree;
import tanager.domain.history.PinnedEntry;
import tanager.domain.history.TreeNode;

/** 트리 노드의 키(순수 계산). */
final class Nodes {
    private Nodes() {}

    /** {@code tree} 의 모든 노드 키(위에서 아래로). '고정됨' 머리는 고정 항목이 있을 때만 있다. */
    static Set<NodeKey> keys(HistoryTree tree) {
        Set<NodeKey> keys = new LinkedHashSet<>();
        if (!tree.pinned().isEmpty()) {
            keys.add(new NodeKey.PinnedSection());
            for (PinnedEntry pinned : tree.pinned()) {
                keys.add(new NodeKey.Pinned(pinned.entry().path()));
            }
        }
        for (EnvironmentNode env : tree.environments()) {
            keys.add(new NodeKey.Environment(env.env()));
            collect(env.children(), keys);
        }
        return keys;
    }

    static NodeKey keyOf(TreeNode.Folder folder) {
        return new NodeKey.Folder(folder.path());
    }

    static NodeKey keyOf(TreeNode.Session session) {
        return new NodeKey.Session(session.session().pane());
    }

    // 이전 대화 · 더 보기는 아직 그리지 않아 키도 없다 — 모양은 사이드바 대화 이슈(#203)가 정한다.
    private static void collect(List<TreeNode> nodes, Set<NodeKey> keys) {
        for (TreeNode node : nodes) {
            switch (node) {
                case TreeNode.Folder folder -> {
                    keys.add(keyOf(folder));
                    collect(folder.children(), keys);
                }
                case TreeNode.Session session -> keys.add(keyOf(session));
                case TreeNode.Conversation _, TreeNode.MoreConversations _ -> {}
            }
        }
    }
}
