package tanager.ui.sidebar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.in.ManageHistoryUseCase;
import tanager.application.port.in.QueryHistoryTreeUseCase;
import tanager.domain.history.DirectoryTree;
import tanager.domain.history.HistoryError;
import tanager.domain.history.HistoryPolicy;
import tanager.domain.history.HistoryTree;
import tanager.domain.history.SessionLeaf;
import tanager.domain.history.SessionStatus;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.TreeQuery;
import tanager.domain.history.VisitedDirectory;
import tanager.domain.history.WorkHistory;
import tanager.domain.history.WorkspacePath;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.Result;
import tanager.ui.theme.ColorToken;
import tanager.ui.theme.SoftDarkLaf;
import tanager.ui.theme.SoftLightLaf;
import tanager.ui.theme.ThemeColors;
import tanager.ui.theme.Themed;

/** 사이드바 패널(Swing). 다시 투영해도 펼침 · 선택이 남고, 몰린 요청은 한 번만 다시 그린다(#84). */
class SidebarPanelTest {
    private static final ShellEnvironment UBUNTU = new ShellEnvironment.Wsl("Ubuntu");
    private static final Instant T0 = Instant.parse("2026-11-27T00:00:00Z");

    private final Queue<Runnable> uiQueue = new ArrayDeque<>();
    private final Executor ui = uiQueue::add;
    private final AtomicReference<HistoryTree> current = new AtomicReference<>();
    private final AtomicReference<WorkHistory> searchable = new AtomicReference<>();
    private final AtomicInteger queries = new AtomicInteger();
    private final AtomicReference<Boolean> lastShowHidden = new AtomicReference<>(false);
    private final AtomicReference<List<SessionLeaf>> live = new AtomicReference<>(List.of());
    private final RecordingManage manage = new RecordingManage();
    private final FakeInteractions interactions = new FakeInteractions();
    private final QueryHistoryTreeUseCase query = (text, showHidden) -> {
        queries.incrementAndGet();
        lastShowHidden.set(showHidden);
        return searchable.get() == null
                ? current.get()
                : DirectoryTree.project(searchable.get(), live.get(), new TreeQuery(text, showHidden, List.of(UBUNTU)));
    };
    private LookAndFeel previous;

    private static WorkspacePath wsl(String raw) {
        return WorkspacePath.of(UBUNTU, raw).value().orElseThrow();
    }

    private static HistoryTree tree(String... visited) {
        WorkHistory history = WorkHistory.empty(HistoryPolicy.DEFAULT);
        for (String raw : visited) {
            history = history.recordVisit(wsl(raw), T0);
        }
        return DirectoryTree.project(history, List.of(), new TreeQuery("", false, List.of(UBUNTU)));
    }

    private static <T> T onEdt(Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> result.set(action.get()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return result.get();
    }

    private static List<String> visibleRows(JTree tree) {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < tree.getRowCount(); i++) {
            Object node = ((DefaultMutableTreeNode) tree.getPathForRow(i).getLastPathComponent()).getUserObject();
            rows.add(((SidebarItem) node).label());
        }
        return rows;
    }

    private static TreePath pathOf(JTree tree, String label) {
        for (int i = 0; i < tree.getRowCount(); i++) {
            TreePath path = tree.getPathForRow(i);
            Object node = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
            if (((SidebarItem) node).label().equals(label)) {
                return path;
            }
        }
        throw new AssertionError("행 없음: " + label + " in " + visibleRows(tree));
    }

    // 행 위를 왼쪽 버튼으로 count 번 누른다
    private static void click(JTree tree, TreePath path, int count) {
        java.awt.Rectangle bounds = tree.getPathBounds(path);
        tree.dispatchEvent(new java.awt.event.MouseEvent(
                tree,
                java.awt.event.MouseEvent.MOUSE_CLICKED,
                0,
                0,
                bounds.x + 2,
                bounds.y + 2,
                count,
                false,
                java.awt.event.MouseEvent.BUTTON1));
    }

    @BeforeEach
    void installTheme() {
        onEdt(() -> {
            previous = UIManager.getLookAndFeel();
            try {
                UIManager.setLookAndFeel(new SoftDarkLaf());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
    }

    @AfterEach
    void restoreTheme() {
        onEdt(() -> {
            try {
                UIManager.setLookAndFeel(previous);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
    }

    @Test
    @DisplayName("테마를 바꾸면 사이드바 · 트리 배경이 새 테마의 sidebar 토큰이다 — 패널은 그대로(#185)")
    void themeChangeRecolorsSidebar() {
        // given
        SidebarPanel panel = onEdt(() -> new SidebarPanel(query, manage, ui, interactions));
        java.awt.Color dark = onEdt(() -> ThemeColors.get(ColorToken.SIDEBAR));

        // when
        onEdt(() -> {
            try {
                UIManager.setLookAndFeel(new SoftLightLaf());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            SwingUtilities.updateComponentTreeUI(panel.component());
            Themed.recolor(panel.component());
            return null;
        });

        // then
        java.awt.Color light = onEdt(() -> ThemeColors.get(ColorToken.SIDEBAR));
        assertThat(light).isNotEqualTo(dark);
        assertThat(onEdt(() -> panel.component().getBackground())).isEqualTo(light);
        assertThat(onEdt(() -> panel.tree().getBackground())).isEqualTo(light);
    }

    @Test
    @DisplayName("처음 그리면 환경 노드가 펼쳐져 있고 폴더 · 환경 글자가 보인다")
    void firstRefreshExpandsEnvironments() {
        // given
        current.set(tree("/home/me/a", "/home/me/b"));

        // when
        List<String> rows = onEdt(() -> {
            SidebarPanel panel = new SidebarPanel(query, manage, ui, interactions);
            panel.refresh();
            return visibleRows(panel.tree());
        });

        // then
        assertThat(rows).containsExactly("WSL · Ubuntu", "/home/me");
    }

    @Test
    @DisplayName("다시 투영해도 펼친 폴더와 선택이 그대로다")
    void refreshKeepsExpansionAndSelection() {
        // given
        current.set(tree("/home/me/a", "/home/me/b"));
        SidebarPanel panel = onEdt(() -> {
            SidebarPanel created = new SidebarPanel(query, manage, ui, interactions);
            created.refresh();
            TreePath home = pathOf(created.tree(), "/home/me");
            created.tree().expandPath(home);
            created.tree().setSelectionPath(home);
            return created;
        });

        // when: 새 방문으로 트리가 바뀐다
        current.set(tree("/home/me/a", "/home/me/b", "/home/me/c"));
        List<String> rows = onEdt(() -> {
            panel.refresh();
            return visibleRows(panel.tree());
        });

        // then
        assertThat(rows).containsExactly("WSL · Ubuntu", "/home/me", "a", "b", "c");
        String selected = onEdt(() -> ((SidebarItem) ((DefaultMutableTreeNode)
                                panel.tree().getSelectionPath().getLastPathComponent())
                        .getUserObject())
                .label());
        assertThat(selected).isEqualTo("/home/me");
    }

    @Test
    @DisplayName("요청이 10번 몰려도 EDT 한 틱에 한 번만 다시 투영한다")
    void coalescesBurstOfRequests() {
        // given
        current.set(tree("/home/me"));
        SidebarPanel panel = onEdt(() -> new SidebarPanel(query, manage, ui, interactions));

        // when
        onEdt(() -> {
            for (int i = 0; i < 10; i++) {
                panel.requestRefresh();
            }
            return null;
        });

        // then
        assertThat(uiQueue).hasSize(1);
        onEdt(() -> {
            uiQueue.poll().run();
            return null;
        });
        assertThat(queries).hasValue(1);
        onEdt(() -> {
            panel.requestRefresh();
            return null;
        });
        assertThat(uiQueue).hasSize(1);
    }

    private static WorkHistory history(String... visited) {
        WorkHistory history = WorkHistory.empty(HistoryPolicy.DEFAULT);
        for (String raw : visited) {
            history = history.recordVisit(wsl(raw), T0);
        }
        return history;
    }

    private SidebarPanel searchablePanel(String... visited) {
        searchable.set(history(visited));
        return onEdt(() -> {
            SidebarPanel created = new SidebarPanel(query, manage, ui, interactions);
            created.refresh();
            return created;
        });
    }

    @Test
    @DisplayName("검색하면 맞은 폴더와 그 조상만 남고 전부 펼쳐 보이며, 맞은 노드가 표시된다(#85)")
    void searchFiltersExpandsAndMarksMatches() {
        // given
        SidebarPanel panel = searchablePanel("/home/me/api", "/home/me/web", "/srv/api-gateway");

        // when
        List<String> rows = onEdt(() -> {
            panel.searchField().setText("api");
            panel.refresh();
            return visibleRows(panel.tree());
        });

        // then: 거른 뒤 압축을 다시 계산해 home/me/api 가 한 줄이 된다(DirectoryTree 규칙)
        assertThat(rows).contains("home/me/api", "srv/api-gateway").noneMatch(row -> row.contains("web"));
        List<Boolean> matched = onEdt(() -> {
            List<Boolean> marks = new ArrayList<>();
            for (int i = 0; i < panel.tree().getRowCount(); i++) {
                SidebarItem item = (SidebarItem)
                        ((DefaultMutableTreeNode) panel.tree().getPathForRow(i).getLastPathComponent()).getUserObject();
                if (item.label().equals("home/me/api")) {
                    marks.add(item.matched());
                }
            }
            return marks;
        });
        assertThat(matched).containsExactly(true);
    }

    @Test
    @DisplayName("검색 중 Esc 는 검색어를 비우고 검색 전 펼침 상태로 돌아간다(#85)")
    void escapeClearsSearchAndRestoresExpansion() {
        // given: /home/me 는 접힌 채(환경만 펼침)
        SidebarPanel panel = searchablePanel("/home/me/a", "/home/me/b");
        List<String> before = onEdt(() -> visibleRows(panel.tree()));
        onEdt(() -> {
            panel.searchField().setText("a");
            panel.refresh();
            return null;
        });

        // when
        List<String> after = onEdt(() -> {
            panel.escape();
            return visibleRows(panel.tree());
        });

        // then
        assertThat(onEdt(() -> panel.searchField().getText())).isEmpty();
        assertThat(after).isEqualTo(before);
    }

    @Test
    @DisplayName("검색어가 비어 있을 때 Esc 는 터미널로 포커스를 돌려보낸다(#85)")
    void escapeOnEmptySearchLeaves() {
        // given
        AtomicInteger left = new AtomicInteger();
        SidebarPanel panel = searchablePanel("/home/me");
        onEdt(() -> {
            panel.onLeave(left::incrementAndGet);
            return null;
        });

        // when
        onEdt(() -> {
            panel.escape();
            return null;
        });

        // then
        assertThat(left).hasValue(1);
    }

    @Test
    @DisplayName("맞는 폴더가 없으면 빈 상태 글자를 보인다(#85)")
    void noResultsShowsEmptyState() {
        // given
        SidebarPanel panel = searchablePanel("/home/me");

        // when
        boolean shown = onEdt(() -> {
            panel.searchField().setText("없는이름");
            panel.refresh();
            return panel.emptyState().isVisible();
        });

        // then
        assertThat(shown).isTrue();
        assertThat(onEdt(() -> panel.emptyState().getText())).isEqualTo(SidebarPanel.NO_MATCH);
        assertThat(onEdt(() -> {
                    panel.searchField().setText("");
                    panel.refresh();
                    return panel.emptyState().isVisible();
                }))
                .isFalse();
    }

    /** 관리 명령을 기록한다. 응답은 쓰지 않는다(트리는 HistoryChanged 로 다시 그린다). */
    private static final class RecordingManage implements ManageHistoryUseCase {
        final List<String> calls = new ArrayList<>();

        private CompletionStage<Result<VisitedDirectory, HistoryError>> record(String call) {
            calls.add(call);
            return new CompletableFuture<>();
        }

        @Override
        public CompletionStage<Result<VisitedDirectory, HistoryError>> pin(WorkspacePath path, boolean pinned) {
            return record("pin " + path.path() + " " + pinned);
        }

        @Override
        public CompletionStage<Result<VisitedDirectory, HistoryError>> alias(
                WorkspacePath path, Optional<String> alias) {
            return record("alias " + path.path() + " " + alias.orElse("(없음)"));
        }

        @Override
        public CompletionStage<Result<VisitedDirectory, HistoryError>> hide(WorkspacePath path, boolean hidden) {
            return record("hide " + path.path() + " " + hidden);
        }

        @Override
        public CompletionStage<Result<VisitedDirectory, HistoryError>> forget(WorkspacePath path) {
            return record("forget " + path.path());
        }
    }

    private static final class FakeInteractions implements SidebarInteractions {
        final List<String> copied = new ArrayList<>();
        final List<String> confirmations = new ArrayList<>();
        boolean confirm = true;
        Optional<Optional<String>> aliasAnswer = Optional.empty();

        @Override
        public void copy(String text) {
            copied.add(text);
        }

        @Override
        public boolean confirmForget(String label) {
            confirmations.add(label);
            return confirm;
        }

        @Override
        public Optional<Optional<String>> editAlias(Optional<String> current) {
            return aliasAnswer;
        }
    }

    /** 사이드바가 연 것을 적어 둔다. */
    private static final class RecordingOpener implements SidebarOpener {
        final List<String> opened = new java.util.ArrayList<>();

        @Override
        public void open(
                tanager.domain.history.WorkspacePath path,
                Optional<tanager.domain.session.ProfileId> profile,
                Optional<tanager.application.port.out.OpenPlacement> placement) {
            opened.add(path.path() + " "
                    + profile.map(tanager.domain.session.ProfileId::value).orElse("-") + " "
                    + placement.map(Enum::name).orElse("-"));
        }

        @Override
        public void openHome(tanager.domain.history.ShellEnvironment env) {
            opened.add("home " + env);
        }

        @Override
        public void openSame(PaneId pane, tanager.application.port.out.OpenPlacement placement) {
            opened.add("same " + pane.value() + " " + placement.name());
        }

        @Override
        public void choices(
                tanager.domain.history.WorkspacePath path,
                java.util.function.Consumer<tanager.application.port.in.OpenChoices> onEdt) {}
    }

    /** 사이드바가 세션 창에 부탁한 것을 적어 둔다(#92). */
    private static final class RecordingSessions implements SidebarSessions {
        final List<String> calls = new java.util.ArrayList<>();

        @Override
        public void focus(PaneId pane) {
            calls.add("focus " + pane.value());
        }

        @Override
        public void restart(PaneId pane) {
            calls.add("restart " + pane.value());
        }

        @Override
        public void close(PaneId pane) {
            calls.add("close " + pane.value());
        }
    }

    private static final SessionLeaf RUNNING = new SessionLeaf(
            new PaneId("p1"), 1, "bash", UBUNTU, Optional.of(wsl("/home/me/a")), new SessionStatus.Running());
    private static final SessionLeaf EXITED =
            new SessionLeaf(new PaneId("p2"), 2, "bash", UBUNTU, Optional.empty(), new SessionStatus.Exited(0));

    @Test
    @DisplayName("세션 노드를 한 번 누르거나 Enter 를 누르면 그 창에 포커스한다(#92)")
    void clickSessionFocusesPane() {
        // given
        live.set(List.of(RUNNING));
        SidebarPanel panel = expandedPanel(history("/home/me/a"));
        RecordingSessions sessions = new RecordingSessions();

        // when
        onEdt(() -> {
            panel.sessions(sessions);
            JTree tree = panel.tree();
            TreePath session = pathOf(tree, SidebarText.session(RUNNING));
            tree.setSelectionPath(session);
            click(tree, session, 1);
            Object name = tree.getInputMap(javax.swing.JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke("ENTER"));
            tree.getActionMap().get(name).actionPerformed(null);
            return null;
        });

        // then
        assertThat(sessions.calls).containsExactly("focus p1", "focus p1");
    }

    @Test
    @DisplayName("폴더를 한 번 누르면 아무것도 열지 않는다 — 여는 것은 더블클릭이다(#92)")
    void singleClickOnFolderDoesNotOpen() {
        // given
        SidebarPanel panel = expandedPanel(history("/home/me/a", "/home/me/b"));
        RecordingOpener opener = new RecordingOpener();
        RecordingSessions sessions = new RecordingSessions();

        // when
        onEdt(() -> {
            panel.opener(opener);
            panel.sessions(sessions);
            JTree tree = panel.tree();
            click(tree, pathOf(tree, "a"), 1);
            return null;
        });

        // then
        assertThat(opener.opened).isEmpty();
        assertThat(sessions.calls).isEmpty();
    }

    @Test
    @DisplayName("세션 메뉴: 포커스 · 같은 경로에 새 창 · 재시작(끝난 세션만) · 닫기를 그 창으로 부른다(#92)")
    void sessionMenuActions() {
        // given
        live.set(List.of(RUNNING, EXITED));
        SidebarPanel panel = expandedPanel(history("/home/me/a"));
        RecordingOpener opener = new RecordingOpener();
        RecordingSessions sessions = new RecordingSessions();

        // when
        onEdt(() -> {
            panel.opener(opener);
            panel.sessions(sessions);
            SidebarItem running = item(panel.tree(), SidebarText.session(RUNNING));
            SidebarItem exited = item(panel.tree(), SidebarText.session(EXITED));
            panel.open(new MenuItem(MenuAction.FOCUS, "포커스", true), running);
            panel.open(new MenuItem(MenuAction.OPEN_RIGHT, "오른쪽", true), running);
            panel.open(new MenuItem(MenuAction.OPEN_DOWN, "아래", true), exited);
            panel.open(new MenuItem(MenuAction.RESTART, "재시작", true), running);
            panel.open(new MenuItem(MenuAction.RESTART, "재시작", true), exited);
            panel.open(new MenuItem(MenuAction.CLOSE, "닫기", true), running);
            return null;
        });

        // then
        assertThat(opener.opened).containsExactly("same p1 RIGHT", "same p2 DOWN");
        assertThat(sessions.calls).containsExactly("focus p1", "restart p2", "close p1");
    }

    @Test
    @DisplayName("폴더를 더블클릭하거나 Enter 를 누르면 설정 자리 · 기본 셸로 그 경로를 연다(#91)")
    void doubleClickAndEnterOpenFolder() {
        // given
        SidebarPanel panel = expandedPanel(history("/home/me/a", "/home/me/b"));
        RecordingOpener opener = new RecordingOpener();

        // when
        onEdt(() -> {
            panel.opener(opener);
            JTree tree = panel.tree();
            TreePath a = pathOf(tree, "a");
            tree.setSelectionPath(a);
            click(tree, a, 2);
            Object name = tree.getInputMap(javax.swing.JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke("ENTER"));
            tree.getActionMap().get(name).actionPerformed(null);
            return null;
        });

        // then
        assertThat(opener.opened).containsExactly("/home/me/a - -", "/home/me/a - -");
    }

    @Test
    @DisplayName("폴더 노드를 끌면 그 경로를 내보낸다 — 세션 · 환경 노드는 끌리지 않는다(#151)")
    void dragExportsFolderOnly() {
        // given
        live.set(List.of(RUNNING));
        SidebarPanel panel = expandedPanel(history("/home/me/a", "/home/me/b"));

        // when
        List<Optional<WorkspacePath>> exported = onEdt(() -> {
            JTree tree = panel.tree();
            FolderDragSource source = (FolderDragSource) tree.getTransferHandler();
            List<Optional<WorkspacePath>> out = new ArrayList<>();
            for (String label : List.of("a", SidebarText.session(RUNNING), SidebarText.environment(UBUNTU))) {
                tree.setSelectionPath(pathOf(tree, label));
                out.add(source.exported(tree).flatMap(FolderTransfer::read));
            }
            return out;
        });
        int actions = onEdt(() -> panel.tree().getTransferHandler().getSourceActions(panel.tree()));

        // then
        assertThat(exported).containsExactly(Optional.of(wsl("/home/me/a")), Optional.empty(), Optional.empty());
        assertThat(actions).isEqualTo(javax.swing.TransferHandler.COPY);
    }

    @Test
    @DisplayName("환경 노드 · 메뉴의 열기 동작은 고른 셸 · 자리로 연다(#91)")
    void menuOpenActions() {
        // given
        SidebarPanel panel = expandedPanel(history("/home/me/a"));
        RecordingOpener opener = new RecordingOpener();

        // when
        onEdt(() -> {
            panel.opener(opener);
            SidebarItem a = item(panel.tree(), "/home/me/a");
            panel.open(new MenuItem(MenuAction.OPEN_DOWN, "아래", true), a);
            panel.open(
                    new MenuItem(
                            MenuAction.OPEN_WITH,
                            "cmd",
                            true,
                            Optional.of(new tanager.domain.session.ProfileId("cmd"))),
                    a);
            panel.open(new MenuItem(MenuAction.NEW_PANE, "새 창", true), item(panel.tree(), "WSL · Ubuntu"));
            return null;
        });

        // then
        assertThat(opener.opened).containsExactly("/home/me/a - DOWN", "/home/me/a cmd -", "home Wsl[distro=Ubuntu]");
    }

    @Test
    @DisplayName("이전 대화 · 더 보기 노드는 아직 행을 만들지 않는다 — 대화가 붙어도 행이 그대로다(#214)")
    void conversationNodesAreNotRendered() {
        // given
        WorkHistory h = history("/home/me/a", "/home/me/b");
        List<tanager.domain.history.PastConversation> conversations = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            conversations.add(new tanager.domain.history.PastConversation(
                    tanager.domain.history.VisitSource.CLAUDE_CODE,
                    new tanager.domain.history.ConversationId("c" + i),
                    wsl("/home/me/a"),
                    Optional.of("대화 " + i),
                    T0.plusSeconds(i)));
        }
        TreeQuery q = new TreeQuery("", false, List.of(UBUNTU));

        // when
        current.set(DirectoryTree.project(h, List.of(), q));
        List<String> without = onEdt(() -> visibleRows(expandAll(new SidebarPanel(query, manage, ui, interactions))));
        current.set(DirectoryTree.project(h, List.of(), conversations, q));
        List<String> with = onEdt(() -> visibleRows(expandAll(new SidebarPanel(query, manage, ui, interactions))));

        // then
        assertThat(with).containsExactlyElementsOf(without).containsExactly("WSL · Ubuntu", "/home/me", "a", "b");
    }

    private static JTree expandAll(SidebarPanel panel) {
        panel.refresh();
        for (int row = 0; row < panel.tree().getRowCount(); row++) {
            panel.tree().expandRow(row);
        }
        return panel.tree();
    }

    private static SidebarItem item(JTree tree, String label) {
        return (SidebarItem) ((DefaultMutableTreeNode) pathOf(tree, label).getLastPathComponent()).getUserObject();
    }

    private SidebarPanel expandedPanel(WorkHistory history) {
        searchable.set(history);
        return onEdt(() -> {
            SidebarPanel created = new SidebarPanel(query, manage, ui, interactions);
            created.refresh();
            for (int row = 0; row < created.tree().getRowCount(); row++) {
                created.tree().expandRow(row);
            }
            return created;
        });
    }

    private static WorkHistory edited(WorkHistory history, java.util.function.UnaryOperator<WorkHistory> change) {
        return change.apply(history);
    }

    @Test
    @DisplayName("경로 복사는 저장된 표기 그대로 클립보드에 넣는다(#86)")
    void copyPathUsesStoredSpelling() {
        // given
        SidebarPanel panel = expandedPanel(history("/home/me/a", "/home/me/b"));

        // when
        onEdt(() -> {
            panel.perform(MenuAction.COPY_PATH, item(panel.tree(), "/home/me"));
            return null;
        });

        // then
        assertThat(interactions.copied).containsExactly("/home/me");
    }

    @Test
    @DisplayName("별칭을 입력하고 확인하면 그 별칭으로 바꾸라고 명령한다(#86)")
    void aliasAsksEditorThenCommands() {
        // given
        SidebarPanel panel = expandedPanel(history("/home/me/a", "/home/me/b"));
        interactions.aliasAnswer = Optional.of(Optional.of("인프라"));

        // when
        onEdt(() -> {
            panel.perform(MenuAction.ALIAS, item(panel.tree(), "a"));
            return null;
        });

        // then
        assertThat(manage.calls).containsExactly("alias /home/me/a 인프라");
    }

    @Test
    @DisplayName("일반 항목의 기록 삭제는 묻지 않고 바로 지운다(#86)")
    void forgetPlainEntryWithoutConfirm() {
        // given
        SidebarPanel panel = expandedPanel(history("/home/me/a", "/home/me/b"));

        // when
        onEdt(() -> {
            panel.perform(MenuAction.FORGET, item(panel.tree(), "a"));
            return null;
        });

        // then
        assertThat(interactions.confirmations).isEmpty();
        assertThat(manage.calls).containsExactly("forget /home/me/a");
    }

    @Test
    @DisplayName("고정 항목의 기록 삭제는 확인을 묻고, 취소하면 지우지 않는다(#86)")
    void forgetPinnedEntryAsks() {
        // given
        SidebarPanel panel = expandedPanel(edited(
                history("/home/me/a", "/home/me/b"),
                h -> h.pin(wsl("/home/me/a"), true).value().orElseThrow()));
        interactions.confirm = false;

        // when
        onEdt(() -> {
            panel.perform(MenuAction.FORGET, item(panel.tree(), "a"));
            return null;
        });

        // then
        assertThat(interactions.confirmations).hasSize(1);
        assertThat(manage.calls).isEmpty();
    }

    @Test
    @DisplayName("숨긴 폴더 보기를 켜면 숨긴 폴더가 흐리게 보이고 메뉴는 숨김 해제다(#86)")
    void showHiddenRevealsHiddenFolders() {
        // given
        SidebarPanel panel = expandedPanel(edited(
                history("/home/me/a", "/home/me/b"),
                h -> h.hide(wsl("/home/me/a"), true).value().orElseThrow()));
        assertThat(onEdt(() -> visibleRows(panel.tree()))).doesNotContain("a");

        // when
        onEdt(() -> {
            panel.perform(MenuAction.TOGGLE_HIDDEN, item(panel.tree(), "WSL · Ubuntu"));
            for (int row = 0; row < panel.tree().getRowCount(); row++) {
                panel.tree().expandRow(row);
            }
            return null;
        });

        // then
        assertThat(lastShowHidden.get()).isTrue();
        SidebarItem hidden = onEdt(() -> item(panel.tree(), "a"));
        assertThat(hidden.hidden()).isTrue();
        assertThat(hidden.target().map(FolderMenu::items).orElseThrow())
                .extracting(MenuItem::action)
                .contains(MenuAction.UNHIDE);
    }

    @Test
    @DisplayName("Delete 키는 고른 항목의 기록을 지운다(#86)")
    void deleteKeyForgetsSelected() {
        // given
        SidebarPanel panel = expandedPanel(history("/home/me/a", "/home/me/b"));

        // when
        onEdt(() -> {
            panel.tree().setSelectionPath(pathOf(panel.tree(), "b"));
            panel.tree().getActionMap().get(SidebarPanel.FORGET_KEY).actionPerformed(null);
            return null;
        });

        // then
        assertThat(manage.calls).containsExactly("forget /home/me/b");
    }

    @Test
    @DisplayName("사라진 경로의 폴더는 흐리게 표시하고 툴팁으로 알린다 — 열기 메뉴는 그대로다(#186)")
    void missingFolderIsMuted() {
        // given
        WorkHistory h = history("/home/me/a", "/home/me/b");
        current.set(DirectoryTree.project(
                h, List.of(), new TreeQuery("", false, List.of(UBUNTU)), java.util.Set.of(wsl("/home/me/a"))));
        SidebarPanel panel = onEdt(() -> {
            SidebarPanel created = new SidebarPanel(query, manage, ui, interactions);
            created.refresh();
            for (int row = 0; row < created.tree().getRowCount(); row++) {
                created.tree().expandRow(row);
            }
            return created;
        });

        // when
        SidebarItem gone = onEdt(() -> item(panel.tree(), "a"));
        SidebarItem here = onEdt(() -> item(panel.tree(), "b"));

        // then
        assertThat(gone.missing()).isTrue();
        assertThat(gone.tooltip()).hasValueSatisfying(t -> assertThat(t).endsWith("이 경로가 더 이상 없다"));
        assertThat(gone.target()).containsInstanceOf(MenuTarget.Folder.class);
        assertThat(here.missing()).isFalse();
    }
}
