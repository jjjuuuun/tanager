package tanager.ui.sidebar;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.ToolTipManager;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import tanager.application.port.in.QueryHistoryTreeUseCase;
import tanager.domain.history.EnvironmentNode;
import tanager.domain.history.HistoryTree;
import tanager.domain.history.PinnedEntry;
import tanager.domain.history.SessionStatus;
import tanager.domain.history.TreeNode;
import tanager.ui.theme.ColorToken;
import tanager.ui.theme.ThemeColors;

/**
 * 시안 D 의 왼쪽 사이드바(#84, FR-HIS-02 · 03 · 04). 위는 '고정됨', 아래는 환경 → 폴더 → 세션. 이력 · 세션 · 환경 순서가 바뀌면 다시
 * 투영하되 펼침 · 선택은 경로 · 창 ID 로 이어 간다({@link ExpansionState}). 모든 메서드는 EDT 에서 부른다.
 */
public final class SidebarPanel {
    /** 기본 폭(저장은 M5). */
    public static final int DEFAULT_WIDTH = 280;

    private final QueryHistoryTreeUseCase query;
    private final Executor ui;
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode();
    private final DefaultTreeModel model = new DefaultTreeModel(root);
    private final JTree tree = new JTree(model);
    private final JPanel panel = new JPanel(new BorderLayout());
    private final Map<NodeKey, DefaultMutableTreeNode> nodes = new HashMap<>();
    private boolean pending;
    private boolean drawn;

    /** @param ui 다시 그리기를 미룰 실행기(EDT 다음 틱) */
    public SidebarPanel(QueryHistoryTreeUseCase query, Executor ui) {
        this.query = Objects.requireNonNull(query, "query");
        this.ui = Objects.requireNonNull(ui, "ui");
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new SidebarRenderer());
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setBackground(ThemeColors.get(ColorToken.SIDEBAR));
        ToolTipManager.sharedInstance().registerComponent(tree);
        JScrollPane scroll = new JScrollPane(tree);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(ThemeColors.get(ColorToken.SIDEBAR));
        panel.setBackground(ThemeColors.get(ColorToken.SIDEBAR));
        panel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, ThemeColors.get(ColorToken.BORDER)));
        panel.setPreferredSize(new Dimension(DEFAULT_WIDTH, 0));
        panel.add(scroll, BorderLayout.CENTER);
    }

    /** 메인 창에 넣을 컴포넌트. */
    public JComponent component() {
        return panel;
    }

    /** 다시 투영을 다음 EDT 틱에 한 번 한다. 그 사이의 요청은 합친다(이벤트가 몰려도 한 번). */
    public void requestRefresh() {
        if (pending) {
            return;
        }
        pending = true;
        ui.execute(() -> {
            pending = false;
            refresh();
        });
    }

    /** 지금 바로 다시 투영한다. 처음에는 환경 노드와 '고정됨'을 펼친다. */
    void refresh() {
        ExpansionState before = capture();
        HistoryTree next = query.tree("", false);
        rebuild(next);
        model.reload();
        ExpansionState.Restored restored = drawn ? before.restoreIn(next) : initial(next);
        drawn = true;
        for (NodeKey key : Nodes.keys(next)) {
            if (restored.expanded().contains(key)) {
                tree.expandPath(pathOf(key));
            }
        }
        restored.selected().ifPresentOrElse(key -> tree.setSelectionPath(pathOf(key)), tree::clearSelection);
    }

    /** 트리(테스트용). */
    JTree tree() {
        return tree;
    }

    private ExpansionState capture() {
        Set<NodeKey> expanded = new java.util.HashSet<>();
        for (Map.Entry<NodeKey, DefaultMutableTreeNode> entry : nodes.entrySet()) {
            if (tree.isExpanded(new TreePath(entry.getValue().getPath()))) {
                expanded.add(entry.getKey());
            }
        }
        Optional<NodeKey> selected = Optional.ofNullable(tree.getSelectionPath())
                .map(path -> ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject())
                .filter(SidebarItem.class::isInstance)
                .map(item -> ((SidebarItem) item).key());
        return new ExpansionState(expanded, selected);
    }

    private static ExpansionState.Restored initial(HistoryTree tree) {
        Set<NodeKey> open = new java.util.HashSet<>();
        if (!tree.pinned().isEmpty()) {
            open.add(new NodeKey.PinnedSection());
        }
        for (EnvironmentNode env : tree.environments()) {
            open.add(new NodeKey.Environment(env.env()));
        }
        return new ExpansionState.Restored(open, Optional.empty());
    }

    private TreePath pathOf(NodeKey key) {
        return new TreePath(nodes.get(key).getPath());
    }

    private void rebuild(HistoryTree next) {
        root.removeAllChildren();
        nodes.clear();
        ZoneId zone = ZoneId.systemDefault();
        if (!next.pinned().isEmpty()) {
            DefaultMutableTreeNode section = add(
                    root,
                    new SidebarItem(
                            new NodeKey.PinnedSection(),
                            SidebarText.PINNED_SECTION,
                            Optional.empty(),
                            Optional.empty(),
                            SidebarItem.Kind.PINNED_SECTION));
            for (PinnedEntry pinned : next.pinned()) {
                add(
                        section,
                        new SidebarItem(
                                new NodeKey.Pinned(pinned.entry().path()),
                                pinned.label(),
                                Optional.empty(),
                                Optional.of(SidebarText.folderTooltip(
                                        pinned.entry().path(), Optional.of(pinned.entry()), zone)),
                                SidebarItem.Kind.PINNED));
            }
        }
        for (EnvironmentNode env : next.environments()) {
            DefaultMutableTreeNode envNode = add(
                    root,
                    new SidebarItem(
                            new NodeKey.Environment(env.env()),
                            SidebarText.environment(env.env()),
                            Optional.empty(),
                            Optional.empty(),
                            SidebarItem.Kind.ENVIRONMENT));
            addAll(envNode, env.children(), zone);
        }
    }

    private void addAll(DefaultMutableTreeNode parent, List<TreeNode> children, ZoneId zone) {
        for (TreeNode child : children) {
            switch (child) {
                case TreeNode.Folder folder -> {
                    DefaultMutableTreeNode node = add(
                            parent,
                            new SidebarItem(
                                    Nodes.keyOf(folder),
                                    folder.label(),
                                    SidebarText.secondary(folder.visit().flatMap(v -> v.alias())),
                                    Optional.of(SidebarText.folderTooltip(folder.path(), folder.visit(), zone)),
                                    SidebarItem.Kind.FOLDER));
                    addAll(node, folder.children(), zone);
                }
                case TreeNode.Session session ->
                    add(
                            parent,
                            new SidebarItem(
                                    Nodes.keyOf(session),
                                    SidebarText.session(session.session()),
                                    Optional.empty(),
                                    session.session().cwd().map(cwd -> cwd.path()),
                                    session.session().status() instanceof SessionStatus.Running
                                            ? SidebarItem.Kind.SESSION_RUNNING
                                            : SidebarItem.Kind.SESSION_EXITED));
            }
        }
    }

    private DefaultMutableTreeNode add(DefaultMutableTreeNode parent, SidebarItem item) {
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(item);
        parent.add(node);
        nodes.put(item.key(), node);
        return node;
    }
}
