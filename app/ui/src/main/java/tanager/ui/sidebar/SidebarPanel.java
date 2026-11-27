package tanager.ui.sidebar;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import tanager.application.port.in.ManageHistoryUseCase;
import tanager.application.port.in.OpenChoices;
import tanager.application.port.in.QueryHistoryTreeUseCase;
import tanager.application.port.out.OpenPlacement;
import tanager.domain.history.Alias;
import tanager.domain.history.EnvironmentNode;
import tanager.domain.history.HistoryError;
import tanager.domain.history.HistoryTree;
import tanager.domain.history.PinnedEntry;
import tanager.domain.history.SessionStatus;
import tanager.domain.history.TreeNode;
import tanager.domain.history.VisitedDirectory;
import tanager.domain.history.WorkspacePath;
import tanager.domain.shared.Result;
import tanager.ui.i18n.Msg;
import tanager.ui.theme.ColorToken;
import tanager.ui.theme.Themed;

/**
 * 시안 D 의 왼쪽 사이드바(#84, FR-HIS-02 · 03 · 04). 위는 '고정됨', 아래는 환경 → 폴더 → 세션. 이력 · 세션 · 환경 순서가 바뀌면 다시
 * 투영하되 펼침 · 선택은 경로 · 창 ID 로 이어 간다({@link ExpansionState}). 모든 메서드는 EDT 에서 부른다.
 */
public final class SidebarPanel {
    /** 기본 폭(저장은 M5). */
    public static final int DEFAULT_WIDTH = 280;
    /** 검색 결과가 없을 때 보이는 글자. */
    static final String NO_MATCH = Msg.SIDEBAR_NO_MATCH.text();

    /** 고른 항목의 기록 삭제(Delete). */
    static final String FORGET_KEY = "tanager.sidebar.forget";

    private static final String MENU_KEY = "tanager.sidebar.menu";
    private static final System.Logger LOG = System.getLogger(SidebarPanel.class.getName());
    private static final String ESCAPE = "tanager.sidebar.escape";
    private static final String TO_TREE = "tanager.sidebar.toTree";
    private static final String OPEN_KEY = "tanager.sidebar.open";

    private final QueryHistoryTreeUseCase query;
    private final ManageHistoryUseCase manage;
    private final Executor ui;
    private final SidebarInteractions interactions;
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode();
    private final DefaultTreeModel model = new DefaultTreeModel(root);
    private final JTree tree = new JTree(model);
    private final JPanel panel = new JPanel(new BorderLayout());
    private final Map<NodeKey, DefaultMutableTreeNode> nodes = new HashMap<>();
    private final JTextField search = new JTextField();
    private final JLabel empty = new JLabel(NO_MATCH);
    private Optional<ExpansionState> beforeSearch = Optional.empty();
    private Runnable leave = () -> {};
    private SidebarOpener opener = SidebarOpener.NONE;
    private SidebarSessions sessions = SidebarSessions.NONE;
    private boolean pending;
    private boolean drawn;
    private boolean searching;
    private boolean showHidden;

    /** @param ui 다시 그리기를 미룰 실행기(EDT 다음 틱) */
    public SidebarPanel(QueryHistoryTreeUseCase query, ManageHistoryUseCase manage, Executor ui) {
        this(query, manage, ui, Optional.empty());
    }

    /** 클립보드 · 대화 상자를 바꿔 끼운다(테스트용). */
    SidebarPanel(
            QueryHistoryTreeUseCase query, ManageHistoryUseCase manage, Executor ui, SidebarInteractions interactions) {
        this(query, manage, ui, Optional.of(interactions));
    }

    private SidebarPanel(
            QueryHistoryTreeUseCase query,
            ManageHistoryUseCase manage,
            Executor ui,
            Optional<SidebarInteractions> interactions) {
        this.query = Objects.requireNonNull(query, "query");
        this.manage = Objects.requireNonNull(manage, "manage");
        this.ui = Objects.requireNonNull(ui, "ui");
        this.interactions = interactions.orElseGet(() -> new SwingInteractions(panel));
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new SidebarRenderer());
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        Themed.background(tree, ColorToken.SIDEBAR);
        ToolTipManager.sharedInstance().registerComponent(tree);
        JScrollPane scroll = new JScrollPane(tree);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        Themed.background(scroll.getViewport(), ColorToken.SIDEBAR);
        Themed.background(panel, ColorToken.SIDEBAR);
        panel.setBorder(Themed.line(0, 0, 0, 1, ColorToken.BORDER));
        panel.setPreferredSize(new Dimension(DEFAULT_WIDTH, 0));
        layoutSearch();
        bindMenu();
        bindOpen();
        bindDrag();
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
        search.putClientProperty("JTextField.placeholderText", Msg.SIDEBAR_SEARCH_PLACEHOLDER.text());
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
        Themed.foreground(empty, ColorToken.MUTED);
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
        HistoryTree next = query.tree(text, showHidden);
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

    /** 창을 여는 길을 붙인다(#91). 메인 창이 만든 뒤 한 번 부른다. EDT 에서 부른다. */
    public void opener(SidebarOpener next) {
        this.opener = Objects.requireNonNull(next, "next");
    }

    /** 세션 창에 부탁하는 길을 붙인다(#92). 메인 창이 만든 뒤 한 번 부른다. EDT 에서 부른다. */
    public void sessions(SidebarSessions next) {
        this.sessions = Objects.requireNonNull(next, "next");
    }

    /** {@code item} 에서 열기 · 세션 메뉴의 {@code entry} 를 한다(#91 · #92). EDT 에서 부른다. */
    void open(MenuItem entry, SidebarItem item) {
        if (!entry.enabled() || item.target().isEmpty()) {
            return;
        }
        MenuTarget target = item.target().orElseThrow();
        switch (entry.action()) {
            case OPEN_RIGHT -> openBeside(target, OpenPlacement.RIGHT);
            case OPEN_DOWN -> openBeside(target, OpenPlacement.DOWN);
            case OPEN_WITH -> folderOf(target).ifPresent(p -> opener.open(p, entry.profile(), Optional.empty()));
            case NEW_PANE -> {
                if (target instanceof MenuTarget.Environment(var env, boolean hidden)) {
                    opener.openHome(env);
                }
            }
            case FOCUS -> sessionOf(target).ifPresent(session -> sessions.focus(session.pane()));
            // 실행 중 강제 재시작은 M4 에서 판단 — 끝난 세션만
            case RESTART ->
                sessionOf(target)
                        .filter(MenuTarget.Session::exited)
                        .ifPresent(session -> sessions.restart(session.pane()));
            case CLOSE -> sessionOf(target).ifPresent(session -> sessions.close(session.pane()));
            default -> perform(entry.action(), item);
        }
    }

    // 폴더는 포커스 창 옆에 그 경로를, 세션은 그 창 옆에 같은 셸 · 같은 경로를 연다
    private void openBeside(MenuTarget target, OpenPlacement placement) {
        switch (target) {
            case MenuTarget.Folder folder -> opener.open(folder.path(), Optional.empty(), Optional.of(placement));
            case MenuTarget.Session session -> opener.openSame(session.pane(), placement);
            case MenuTarget.Environment _ -> {}
        }
    }

    private static Optional<WorkspacePath> folderOf(MenuTarget target) {
        return target instanceof MenuTarget.Folder folder ? Optional.of(folder.path()) : Optional.empty();
    }

    private static Optional<MenuTarget.Session> sessionOf(MenuTarget target) {
        return target instanceof MenuTarget.Session session ? Optional.of(session) : Optional.empty();
    }

    // 폴더 더블클릭 · Enter = 설정 자리에 기본 셸로 열기(§8.4). 더블클릭은 펼치기 대신 열기다 — 펼치기는 화살표 · 손잡이.
    // 세션은 한 번 누르기 · Enter = 그 창 포커스(#92) — 사이드바 포커스는 남기지 않고 터미널로
    private void bindOpen() {
        tree.setToggleClickCount(0);
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                Optional<SidebarItem> clicked = Optional.ofNullable(tree.getPathForLocation(e.getX(), e.getY()))
                        .map(path -> ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject())
                        .filter(SidebarItem.class::isInstance)
                        .map(SidebarItem.class::cast);
                if (e.getClickCount() == 1) {
                    clicked.ifPresent(SidebarPanel.this::focusSession);
                } else if (e.getClickCount() == 2) {
                    clicked.ifPresent(SidebarPanel.this::openFolder);
                }
            }
        });
        tree.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), OPEN_KEY);
        tree.getActionMap().put(OPEN_KEY, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                selectedItem().ifPresent(item -> {
                    openFolder(item);
                    focusSession(item);
                });
            }
        });
    }

    // 폴더를 창 영역으로 끌어 그 자리에 연다(#151). 더블클릭 · Enter · 메뉴는 그대로다. JTree 는 화면이 없으면 끌기를 켤 수 없다
    // (HeadlessException) — 테스트는 내보내는 값만 본다
    private void bindDrag() {
        tree.setTransferHandler(new FolderDragSource(Optional.ofNullable(tree.getTransferHandler())));
        if (!GraphicsEnvironment.isHeadless()) {
            tree.setDragEnabled(true);
        }
    }

    private void openFolder(SidebarItem item) {
        item.target()
                .flatMap(SidebarPanel::folderOf)
                .ifPresent(p -> opener.open(p, Optional.empty(), Optional.empty()));
    }

    private void focusSession(SidebarItem item) {
        item.target().flatMap(SidebarPanel::sessionOf).ifPresent(session -> sessions.focus(session.pane()));
    }

    /** {@code item} 에서 메뉴의 {@code action} 을 한다(#86). 비활성 동작은 하지 않는다. EDT 에서 부른다. */
    void perform(MenuAction action, SidebarItem item) {
        Optional<MenuTarget> target = item.target();
        boolean enabled = target.map(FolderMenu::items).orElse(List.of()).stream()
                .anyMatch(menu -> menu.action() == action && menu.enabled());
        if (!enabled) {
            return;
        }
        switch (action) {
            case COPY_PATH -> pathOf(target.orElseThrow()).ifPresent(path -> interactions.copy(path.path()));
            case PIN, UNPIN -> visitOf(item).ifPresent(v -> report(manage.pin(v.path(), action == MenuAction.PIN)));
            case ALIAS ->
                visitOf(item)
                        .ifPresent(v -> interactions
                                .editAlias(v.alias().map(Alias::value))
                                .ifPresent(alias -> report(manage.alias(v.path(), alias))));
            case HIDE, UNHIDE -> visitOf(item).ifPresent(v -> report(manage.hide(v.path(), action == MenuAction.HIDE)));
            case FORGET ->
                visitOf(item)
                        .filter(v -> !FolderMenu.needsConfirm(v) || interactions.confirmForget(item.label()))
                        .ifPresent(v -> report(manage.forget(v.path())));
            case TOGGLE_HIDDEN -> {
                showHidden = !showHidden;
                refresh();
            }
            case EXPAND_ALL, COLLAPSE_ALL -> expandAll(item.key(), action == MenuAction.EXPAND_ALL);
            // 열기 · 세션 동작은 open(MenuItem, …) 이 한다 — FolderMenu 항목이 아니라 여기까지 오지 않는다
            case OPEN_RIGHT, OPEN_DOWN, OPEN_WITH, NEW_PANE, FOCUS, RESTART, CLOSE -> {}
        }
    }

    private static Optional<WorkspacePath> pathOf(MenuTarget target) {
        return switch (target) {
            case MenuTarget.Folder folder -> Optional.of(folder.path());
            case MenuTarget.Session session -> session.cwd();
            case MenuTarget.Environment _ -> Optional.empty();
        };
    }

    private static Optional<VisitedDirectory> visitOf(SidebarItem item) {
        return item.target()
                .filter(MenuTarget.Folder.class::isInstance)
                .flatMap(target -> ((MenuTarget.Folder) target).visit());
    }

    // 바뀐 이력은 HistoryChanged 로 트리에 다시 그려진다. 거부(그사이 지워짐 등)는 남기기만 한다
    private static void report(CompletionStage<Result<VisitedDirectory, HistoryError>> reply) {
        reply.whenComplete((result, failure) -> {
            if (failure != null) {
                LOG.log(System.Logger.Level.WARNING, "이력을 바꾸지 못했다", failure);
            } else {
                result.error().ifPresent(error -> LOG.log(System.Logger.Level.INFO, "이력 변경 거부: {0}", error));
            }
        });
    }

    private void expandAll(NodeKey key, boolean expand) {
        DefaultMutableTreeNode top = nodes.get(key);
        java.util.Enumeration<javax.swing.tree.TreeNode> all = top.depthFirstEnumeration();
        List<TreePath> paths = new java.util.ArrayList<>();
        while (all.hasMoreElements()) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) all.nextElement();
            if (!node.isLeaf()) {
                paths.add(new TreePath(node.getPath()));
            }
        }
        // 펼칠 때는 위에서 아래로, 접을 때는 아래에서 위로
        if (expand) {
            java.util.Collections.reverse(paths);
            paths.forEach(tree::expandPath);
        } else {
            paths.forEach(tree::collapsePath);
        }
    }

    // 마우스 오른쪽 · 메뉴 키 · Shift+F10 으로 메뉴, Delete 로 기록 삭제(§8.4)
    private void bindMenu() {
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                popupIfTrigger(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                popupIfTrigger(e);
            }
        });
        tree.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("CONTEXT_MENU"), MENU_KEY);
        tree.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("shift F10"), MENU_KEY);
        tree.getActionMap().put(MENU_KEY, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                Optional.ofNullable(tree.getSelectionPath()).ifPresent(path -> {
                    java.awt.Rectangle bounds = tree.getPathBounds(path);
                    showMenu(path, bounds.x, bounds.y + bounds.height);
                });
            }
        });
        tree.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("DELETE"), FORGET_KEY);
        tree.getActionMap().put(FORGET_KEY, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                selectedItem().ifPresent(item -> perform(MenuAction.FORGET, item));
            }
        });
    }

    private void popupIfTrigger(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        Optional.ofNullable(tree.getPathForLocation(e.getX(), e.getY())).ifPresent(path -> {
            tree.setSelectionPath(path);
            showMenu(path, e.getX(), e.getY());
        });
    }

    private void showMenu(TreePath path, int x, int y) {
        Object user = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        if (!(user instanceof SidebarItem item) || item.target().isEmpty()) {
            return;
        }
        MenuTarget target = item.target().orElseThrow();
        // 폴더의 열기 후보는 셸 목록 · 설정 · 경로 변환이 필요해 EDT 밖에서 구한 뒤 띄운다
        if (target instanceof MenuTarget.Folder folder) {
            opener.choices(folder.path(), choices -> popup(item, OpenMenu.entries(target, choices), x, y));
        } else {
            popup(item, OpenMenu.entries(target, new OpenChoices(Optional.empty(), List.of())), x, y);
        }
    }

    private void popup(SidebarItem item, List<MenuEntry> opens, int x, int y) {
        JPopupMenu menu = new JPopupMenu();
        for (MenuEntry entry : opens) {
            menu.add(component(entry, item));
        }
        if (!opens.isEmpty()) {
            menu.addSeparator();
        }
        for (MenuItem entry : FolderMenu.items(item.target().orElseThrow())) {
            menu.add(component(entry, item));
        }
        if (tree.isShowing()) {
            menu.show(tree, x, y);
        }
    }

    private javax.swing.JMenuItem component(MenuEntry entry, SidebarItem item) {
        return switch (entry) {
            case MenuItem one -> {
                JMenuItem row = new JMenuItem(one.label());
                row.setEnabled(one.enabled());
                row.addActionListener(event -> open(one, item));
                yield row;
            }
            case Submenu sub -> {
                javax.swing.JMenu menu = new javax.swing.JMenu(sub.label());
                menu.setEnabled(sub.enabled());
                sub.reason().ifPresent(menu::setToolTipText);
                for (MenuItem one : sub.items()) {
                    menu.add(component(one, item));
                }
                yield menu;
            }
        };
    }

    private Optional<SidebarItem> selectedItem() {
        return Optional.ofNullable(tree.getSelectionPath())
                .map(path -> ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject())
                .filter(SidebarItem.class::isInstance)
                .map(SidebarItem.class::cast);
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
                            false,
                            Optional.empty(),
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
                                pinned.matched(),
                                Optional.of(new MenuTarget.Folder(pinned.entry().path(), Optional.of(pinned.entry()))),
                                false));
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
                            false,
                            Optional.of(new MenuTarget.Environment(env.env(), showHidden)),
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
                                    folder.matched(),
                                    Optional.of(new MenuTarget.Folder(folder.path(), folder.visit())),
                                    folder.visit().map(v -> v.hidden()).orElse(false)));
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
                                    false,
                                    Optional.of(new MenuTarget.Session(
                                            session.session().pane(),
                                            session.session().cwd(),
                                            session.session().status() instanceof SessionStatus.Exited)),
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
