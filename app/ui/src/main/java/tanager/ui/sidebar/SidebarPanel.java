package tanager.ui.sidebar;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.ToolTipManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
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
    /** 검색 결과가 없을 때 보이는 글자. */
    static final String NO_MATCH = "맞는 폴더 없음";

    private static final String ESCAPE = "tanager.sidebar.escape";
    private static final String TO_TREE = "tanager.sidebar.toTree";

    private final QueryHistoryTreeUseCase query;
    private final Executor ui;
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode();
    private final DefaultTreeModel model = new DefaultTreeModel(root);
    private final JTree tree = new JTree(model);
    private final JPanel panel = new JPanel(new BorderLayout());
    private final Map<NodeKey, DefaultMutableTreeNode> nodes = new HashMap<>();
    private final JTextField search = new JTextField();
    private final JLabel empty = new JLabel(NO_MATCH);
    private Optional<ExpansionState> beforeSearch = Optional.empty();
    private Runnable leave = () -> {};
    private boolean pending;
    private boolean drawn;
    private boolean searching;

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
        layoutSearch();
        JPanel body = new JPanel(new BorderLayout());
        body.setOpaque(false);
        body.add(empty, BorderLayout.NORTH);
        body.add(scroll, BorderLayout.CENTER);
        panel.add(search, BorderLayout.NORTH);
        panel.add(body, BorderLayout.CENTER);
    }

    /** 검색칸으로 포커스(Ctrl+Shift+F, #85). 검색어는 모두 고른다. */
    public void focusSearch() {
        search.requestFocusInWindow();
        search.selectAll();
    }

    /** 검색어가 빈 채로 Esc 를 누르면 부른다(터미널로 포커스, #85). */
    public void onLeave(Runnable action) {
        this.leave = Objects.requireNonNull(action, "action");
    }

    /**
     * 검색칸의 Esc: 검색어가 있으면 비우고 검색 전 펼침 상태로 돌아가고, 비어 있으면 {@link #onLeave} 의 동작을 한다. 검색어는 저장하지
     * 않는다.
     */
    void escape() {
        if (search.getText().isEmpty()) {
            leave.run();
            return;
        }
        search.setText("");
        refresh();
    }

    // 입력마다 다시 거른다(2000개 기준 수 ms, 한 틱에 한 번으로 합친다). ↓ 는 트리의 첫 결과로
    private void layoutSearch() {
        search.putClientProperty("JTextField.placeholderText", "폴더 검색  Ctrl+Shift+F");
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                requestRefresh();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                requestRefresh();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                requestRefresh();
            }
        });
        search.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ESCAPE"), ESCAPE);
        search.getActionMap().put(ESCAPE, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                escape();
            }
        });
        search.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("DOWN"), TO_TREE);
        search.getActionMap().put(TO_TREE, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (tree.getRowCount() > 0) {
                    tree.setSelectionRow(firstMatchRow());
                    tree.requestFocusInWindow();
                }
            }
        });
        empty.setForeground(ThemeColors.get(ColorToken.MUTED));
        empty.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        empty.setVisible(false);
    }

    private int firstMatchRow() {
        for (int row = 0; row < tree.getRowCount(); row++) {
            Object node = ((DefaultMutableTreeNode) tree.getPathForRow(row).getLastPathComponent()).getUserObject();
            if (node instanceof SidebarItem item && item.matched()) {
                return row;
            }
        }
        return 0;
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

    /**
     * 지금 바로 다시 투영한다. 처음에는 환경 노드와 '고정됨'을 펼친다. 검색 중에는 전부 펼치고, 검색이 끝나면 검색 전 펼침 상태로 돌아간다.
     */
    void refresh() {
        ExpansionState before = capture();
        String text = search.getText();
        boolean nowSearching = !text.isBlank();
        if (nowSearching && !searching) {
            beforeSearch = Optional.of(before);
        }
        HistoryTree next = query.tree(text, false);
        rebuild(next);
        model.reload();
        ExpansionState.Restored restored;
        if (!drawn) {
            restored = initial(next);
        } else if (nowSearching) {
            restored = new ExpansionState.Restored(
                    Nodes.keys(next), before.restoreIn(next).selected());
        } else if (searching) {
            restored = beforeSearch.orElse(before).restoreIn(next);
            beforeSearch = Optional.empty();
        } else {
            restored = before.restoreIn(next);
        }
        drawn = true;
        searching = nowSearching;
        empty.setVisible(nowSearching && !hasMatch(next));
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

    /** 검색칸(테스트용). */
    JTextField searchField() {
        return search;
    }

    /** 결과 없음 글자(테스트용). */
    JLabel emptyState() {
        return empty;
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

    // 맞은 것이 없어도 환경 노드는 남을 수 있다 — 맞은 노드가 있는지로 본다
    private static boolean hasMatch(HistoryTree tree) {
        if (tree.pinned().stream().anyMatch(PinnedEntry::matched)) {
            return true;
        }
        return tree.environments().stream().anyMatch(env -> hasMatch(env.children()));
    }

    private static boolean hasMatch(List<TreeNode> nodes) {
        for (TreeNode node : nodes) {
            if (node instanceof TreeNode.Folder folder && (folder.matched() || hasMatch(folder.children()))) {
                return true;
            }
        }
        return false;
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
                            SidebarItem.Kind.PINNED_SECTION,
                            false));
            for (PinnedEntry pinned : next.pinned()) {
                add(
                        section,
                        new SidebarItem(
                                new NodeKey.Pinned(pinned.entry().path()),
                                pinned.label(),
                                Optional.empty(),
                                Optional.of(SidebarText.folderTooltip(
                                        pinned.entry().path(), Optional.of(pinned.entry()), zone)),
                                SidebarItem.Kind.PINNED,
                                pinned.matched()));
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
                            SidebarItem.Kind.ENVIRONMENT,
                            false));
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
                                    SidebarItem.Kind.FOLDER,
                                    folder.matched()));
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
                                            : SidebarItem.Kind.SESSION_EXITED,
                                    false));
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
