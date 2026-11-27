package tanager.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuBar;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import tanager.application.port.in.ClosePaneUseCase;
import tanager.application.port.in.FocusPaneUseCase;
import tanager.application.port.in.ListProfilesUseCase;
import tanager.application.port.in.ManageHistoryUseCase;
import tanager.application.port.in.OpenPaneUseCase;
import tanager.application.port.in.QueryHistoryTreeUseCase;
import tanager.application.port.in.StartSessionUseCase;
import tanager.domain.history.WorkspacePath;
import tanager.domain.shared.PaneId;
import tanager.domain.workspace.Workspace;
import tanager.ui.i18n.Msg;
import tanager.ui.layout.LayoutView;
import tanager.ui.layout.PaneDiff;
import tanager.ui.pane.PaneController;
import tanager.ui.sidebar.SidebarPanel;
import tanager.ui.sidebar.SidebarSessions;
import tanager.ui.terminal.TerminalSettings;
import tanager.ui.theme.ColorToken;
import tanager.ui.theme.ThemeColors;

/**
 * 메인 창(시안 D): 제목 표시줄 안 탭 · 사이드바 · 분할한 터미널 창들(#89) · 상태줄. EDT 에서만 만진다.
 *
 * <p>앱 종료 순서(ADR-0011): 셸 닫기(기동 중이면 기동이 끝난 뒤) → 창 dispose(바로 사라진다) → 비데몬 스레드가 앱 종료 훅(남은 명령 처리 ·
 * 미저장 이력 저장 최대 2초)을 데몬 스레드에서 돌리고 기한까지만 기다림 → 셸 종료 대기 → {@code System.exit(0)}. 훅과 셸 대기는 닫은 때부터 4초 안에 끝내고, 훅이
 * 멈추거나 Error 가 나도 끝낸다({@link ExitSequence}).
 */
final class MainWindow {
    private static final System.Logger LOG = System.getLogger(MainWindow.class.getName());
    private static final int WIDTH = 1200;
    private static final int HEIGHT = 800;
    private static final int MIN_WIDTH = 640;
    private static final int MIN_HEIGHT = 400;
    private static final int GAP = 6;
    private static final int STATUS_HEIGHT = 28;
    // pty 어댑터는 close 뒤 3초 유예하고 남은 자손을 강제 종료한다(그 뒤에야 루트의 onExit 가 완료될 수 있다). 그보다 길게
    // 기다려 강제 종료 경로까지 덮는다. 루트가 유예 안에 끝나면 자손 정리 스레드(어댑터의 가상 스레드)가 System.exit 와
    // 경합할 수 있다 — 포트에 "정리 끝" 신호가 없어서 남은 한계다(ADR-0011)
    private static final long EXIT_WAIT_SECONDS = 4;

    private final JFrame frame = new JFrame("Tanager");
    private final JLabel tab = new JLabel("Tanager");
    private final JLabel statusLeft = new JLabel();
    private final JLabel statusRight = new JLabel();
    private Notices notices = new Notices(List.of());
    // 창 ID → 컨트롤러(트리의 창 순서). 작업 공간(StateActor)의 사본만 들고, 바꾸는 일은 유스케이스로 한다(#89)
    private final Map<PaneId, PaneController> panes = new LinkedHashMap<>();
    private final Map<PaneId, View> views = new HashMap<>();
    private Workspace workspace = Workspace.initial();
    private final LayoutView layout;
    private final SidebarPanel sidebar;
    private final KeyEventDispatcher shortcuts = this::shortcut;
    private final Runnable onShutdown;
    private final StartSessionUseCase sessions;
    private final ListProfilesUseCase profiles;
    private final ClosePaneUseCase closePane;
    private final FocusPaneUseCase focusPane;
    private final TerminalSettings settings;
    private final Executor ui = SwingUtilities::invokeLater;
    private final Executor background;
    private boolean closing;

    MainWindow(
            StartSessionUseCase sessions,
            ListProfilesUseCase profiles,
            QueryHistoryTreeUseCase historyTree,
            ManageHistoryUseCase historyManager,
            ClosePaneUseCase closePane,
            FocusPaneUseCase focusPane,
            OpenPaneUseCase open,
            Runnable onShutdown,
            TerminalSettings settings,
            Executor background) {
        this.onShutdown = Objects.requireNonNull(onShutdown, "onShutdown");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.closePane = Objects.requireNonNull(closePane, "closePane");
        this.focusPane = Objects.requireNonNull(focusPane, "focusPane");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.background = Objects.requireNonNull(background, "background");
        for (PaneId id : workspace.tab().root().panes()) {
            panes.put(id, newPane(id));
        }
        this.layout = new LayoutView(workspace.tab().root(), GAP);
        this.sidebar = new SidebarPanel(historyTree, historyManager, ui);
        sidebar.onLeave(() -> focused().focus());
        sidebar.opener(new PaneOpener(Objects.requireNonNull(open, "open"), background, ui, this::notify));
        sidebar.sessions(new Sessions());
        frame.getContentPane().add(sidebar.component(), BorderLayout.WEST);
        // 터미널(JediTerm)이 키를 먼저 먹지 않게 포커스와 상관없이 가로챈다
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(shortcuts);
        frame.setJMenuBar(titleBarTabs());
        frame.getContentPane().add(paneArea(), BorderLayout.CENTER);
        frame.getContentPane().add(statusBar(), BorderLayout.SOUTH);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                closeRequested();
            }
        });
        frame.setSize(WIDTH, HEIGHT);
        frame.setMinimumSize(new Dimension(MIN_WIDTH, MIN_HEIGHT));
        frame.setLocationRelativeTo(null);
    }

    /** 창을 띄우고(아직 연결하지 않은 위젯이 놓인다) 첫 셸들을 띄운다. */
    void show(List<String> warnings) {
        frame.setVisible(true);
        sidebar.requestRefresh();
        if (!warnings.isEmpty()) {
            notify(warnings);
        }
        layout.show(workspace.tab().root(), components());
        for (PaneId id : workspace.tab().root().panes()) {
            panes.get(id).start(workspace.spec(id).orElseThrow());
        }
    }

    /** 이력 · 세션 · 환경 순서가 바뀌었다 — 사이드바를 다음 틱에 한 번 다시 그린다(#84). EDT 에서 부른다. */
    void historyChanged() {
        sidebar.requestRefresh();
    }

    /**
     * 작업 공간이 바뀌었다(#89). 사라진 창은 셸을 닫고 떼고, 새 창은 놓은 뒤 명세대로 셸을 띄운다. 포커스가 바뀌었으면 그 창으로 키보드 포커스를
     * 옮기고 상태줄을 그 창 기준으로 바꾼다. EDT 에서 부른다.
     */
    void layoutChanged(Workspace next) {
        if (closing) {
            return;
        }
        PaneDiff diff = PaneDiff.between(workspace, next);
        boolean focusMoved = !next.tab().focused().equals(workspace.tab().focused());
        workspace = next;
        for (PaneId id : diff.removed()) {
            PaneController removed = panes.remove(id);
            views.remove(id);
            if (removed != null) {
                removed.shutdown();
            }
        }
        for (PaneId id : diff.added()) {
            panes.put(id, newPane(id));
        }
        layout.show(next.tab().root(), components());
        for (PaneId id : diff.added()) {
            panes.get(id).start(next.spec(id).orElseThrow());
        }
        if (focusMoved || !diff.isEmpty()) {
            focused().focus();
        }
        showStatusOf(next.tab().focused());
    }

    /** 마지막 창을 닫았다 — 앱을 끝낸다(ADR-0011 · 0016). EDT 에서 부른다. */
    void lastPaneClosed() {
        exit();
    }

    // Ctrl+Shift+E: 사이드바 보이기 · 숨기기, Ctrl+Shift+F: 사이드바 검색(SDD §8.2). 전환 효과는 M4(FR-UI-04)
    private boolean shortcut(KeyEvent event) {
        boolean ours = event.getID() == KeyEvent.KEY_PRESSED
                && event.isControlDown()
                && event.isShiftDown()
                && !event.isAltDown()
                && SwingUtilities.getWindowAncestor(event.getComponent()) == frame;
        if (ours && event.getKeyCode() == KeyEvent.VK_E) {
            sidebar.component().setVisible(!sidebar.component().isVisible());
            frame.getContentPane().revalidate();
            return true;
        }
        if (ours && event.getKeyCode() == KeyEvent.VK_F) {
            sidebar.component().setVisible(true);
            frame.getContentPane().revalidate();
            sidebar.focusSearch();
            return true;
        }
        return false;
    }

    /** 셸 통합이 알린 경로를 그 창에 넘긴다(#82). 모르는 창이면 버린다. EDT 에서 부른다. */
    void cwdChanged(PaneId paneId, WorkspacePath cwd) {
        PaneController pane = panes.get(paneId);
        if (pane != null) {
            pane.cwdReported(cwd);
        }
    }

    void notify(List<String> messages) {
        for (String message : messages) {
            LOG.log(System.Logger.Level.WARNING, "알림: {0}", message);
        }
        notices = notices.add(messages);
        notices.summary().ifPresent(summary -> {
            statusRight.setText(summary);
            statusRight.setToolTipText(tooltip(notices.messages()));
        });
    }

    private PaneController newPane(PaneId id) {
        return new PaneController(id, ui, background, sessions, profiles, settings, new Host(id));
    }

    private PaneController focused() {
        return panes.get(workspace.tab().focused());
    }

    private Map<PaneId, JComponent> components() {
        Map<PaneId, JComponent> components = new LinkedHashMap<>();
        panes.forEach((id, pane) -> components.put(id, pane.component()));
        return components;
    }

    // 탭 이름 · 상태줄 왼쪽은 포커스 창 기준이다(#89)
    private void showStatusOf(PaneId id) {
        View view = views.get(id);
        if (view != null) {
            tab.setText(view.title());
            statusLeft.setText(view.status());
        }
    }

    // 셸이 여럿 떠 있으면 한꺼번에 잃지 않게 묻는다(U2). 하나면 지금처럼 바로 끝낸다
    private void closeRequested() {
        int running =
                (int) panes.values().stream().filter(PaneController::running).count();
        if (ExitPrompt.needed(running)) {
            int answer = JOptionPane.showConfirmDialog(
                    frame,
                    Msg.EXIT_CONFIRM.text(running),
                    Msg.EXIT_CONFIRM_TITLE.text(),
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.OK_OPTION) {
                return;
            }
        }
        exit();
    }

    private void exit() {
        if (closing) {
            return;
        }
        closing = true;
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(shortcuts);
        List<CompletableFuture<?>> exits = new ArrayList<>();
        for (PaneController pane : panes.values()) {
            exits.add(pane.shutdown().toCompletableFuture());
        }
        CompletionStage<?> exited = CompletableFuture.allOf(exits.toArray(CompletableFuture[]::new));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(EXIT_WAIT_SECONDS);
        frame.dispose();
        // 비데몬 스레드: 마지막 창이 사라져도 이 대기가 끝나기 전에 JVM 이 멈추지 않는다. 셸이 끝나는 동안 이력을 저장한다
        Thread.ofPlatform()
                .daemon(false)
                .name("tanager-exit")
                .start(() -> ExitSequence.run(onShutdown, exited, deadline, () -> System.exit(0)));
    }

    /** 창 하나가 마지막으로 알린 탭 이름 · 상태줄 왼쪽 글자. */
    private record View(String title, String status) {}

    /** 창 하나의 알림을 받는다. 상태줄은 포커스 창 것만 보인다. */
    private final class Host implements PaneController.Host {
        private final PaneId id;

        Host(PaneId id) {
            this.id = id;
        }

        @Override
        public void viewChanged(String tabTitle, String status) {
            views.put(id, new View(tabTitle, status));
            if (workspace.tab().focused().equals(id)) {
                showStatusOf(id);
            }
        }

        @Override
        public void notify(List<String> messages) {
            MainWindow.this.notify(messages);
        }

        // 끝난 창의 Esc: 작업 공간에서 닫는다. 마지막 창이면 LastPaneClosed 가 와서 앱이 끝난다
        @Override
        public void removed() {
            closePane.close(id);
        }

        @Override
        public void focusGained() {
            if (!closing && !workspace.tab().focused().equals(id) && panes.containsKey(id)) {
                focusPane.focus(id);
            }
        }
    }

    /** 사이드바 세션 노드의 포커스 · 재시작 · 닫기(#92). 바꾸는 일은 유스케이스로 하고 화면은 LayoutChanged 로 따라온다. */
    private final class Sessions implements SidebarSessions {
        // 포커스가 바뀌면 LayoutChanged 가 키보드 포커스를 옮긴다. 이미 포커스 창이면 이벤트가 없으니 직접 옮긴다
        @Override
        public void focus(PaneId pane) {
            PaneController target = panes.get(pane);
            if (closing || target == null) {
                return;
            }
            if (workspace.tab().focused().equals(pane)) {
                target.focus();
            } else {
                focusPane.focus(pane);
            }
        }

        @Override
        public void restart(PaneId pane) {
            PaneController target = panes.get(pane);
            if (closing || target == null) {
                return;
            }
            target.restart();
            focus(pane);
        }

        // 실행 중인 셸도 묻지 않는다(명시적 선택). 셸은 LayoutChanged 에서 창을 뗄 때 닫히고, 마지막 창이면 LastPaneClosed 로 앱이 끝난다
        @Override
        public void close(PaneId pane) {
            if (!closing && panes.containsKey(pane)) {
                closePane.close(pane);
            }
        }
    }

    // FlatLaf 창 장식이 메뉴 막대를 제목 표시줄 안에 끼운다. 탭은 누르는 기능이 없고 그 자리로 창을 끌 수 있다
    private JMenuBar titleBarTabs() {
        JMenuBar bar = new JMenuBar();
        bar.setOpaque(false);
        bar.setBorder(new EmptyBorder(0, 0, 0, 0));
        tab.setForeground(ThemeColors.get(ColorToken.TEXT));
        tab.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(2, 0, 0, 0, ThemeColors.get(ColorToken.ACCENT)),
                new EmptyBorder(0, 14, 0, 14)));
        tab.putClientProperty(FlatClientProperties.COMPONENT_TITLE_BAR_CAPTION, true);
        bar.add(tab);
        return bar;
    }

    private JPanel paneArea() {
        JPanel area = new JPanel(new BorderLayout());
        area.setBackground(ThemeColors.get(ColorToken.WINDOW));
        area.setBorder(new EmptyBorder(GAP, GAP, GAP, GAP));
        area.add(layout.component(), BorderLayout.CENTER);
        return area;
    }

    private JPanel statusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(ThemeColors.get(ColorToken.HEADER));
        bar.setPreferredSize(new Dimension(0, STATUS_HEIGHT));
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, ThemeColors.get(ColorToken.BORDER)),
                new EmptyBorder(0, 10, 0, 10)));
        statusLeft.setForeground(ThemeColors.get(ColorToken.MUTED));
        statusRight.setForeground(ThemeColors.get(ColorToken.WARN));
        bar.add(statusLeft, BorderLayout.WEST);
        bar.add(statusRight, BorderLayout.EAST);
        return bar;
    }

    private static String tooltip(List<String> messages) {
        StringBuilder html = new StringBuilder("<html>");
        for (int i = 0; i < messages.size(); i++) {
            if (i > 0) {
                html.append("<br>");
            }
            html.append(escape(messages.get(i)));
        }
        return html.append("</html>").toString();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
