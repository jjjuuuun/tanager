package tanager.ui.pane;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import tanager.application.port.in.ListProfilesUseCase;
import tanager.application.port.in.StartFailure;
import tanager.application.port.in.StartNotice;
import tanager.application.port.in.StartRequest;
import tanager.application.port.in.StartSessionUseCase;
import tanager.application.port.in.StartedSession;
import tanager.application.port.out.TerminalSize;
import tanager.application.port.out.TerminalStream;
import tanager.domain.history.WorkspacePath;
import tanager.domain.layout.Orientation;
import tanager.domain.session.ShellProfile;
import tanager.domain.session.WorkingDir;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.Result;
import tanager.domain.workspace.PaneSpec;
import tanager.ui.i18n.Msg;
import tanager.ui.i18n.NoticeText;
import tanager.ui.keys.KeyHelp;
import tanager.ui.keys.LayoutAction;
import tanager.ui.terminal.StreamTtyConnector;
import tanager.ui.terminal.TanagerTermWidget;
import tanager.ui.terminal.TerminalSettings;
import tanager.ui.theme.ColorToken;
import tanager.ui.theme.ThemeColors;

/**
 * 터미널 창 하나(Swing 쪽). 상태 전이는 {@link PaneState} 가 계산하고 여기서는 효과만 실행한다(ADR-0011). 모든 메서드는 EDT 에서
 * 부른다 — 셸 기동은 {@code background} 에서 하고 결과는 {@code ui} 로 돌아온다.
 */
public final class PaneController {
    private static final System.Logger LOG = System.getLogger(PaneController.class.getName());
    private static final int HEADER_HEIGHT = 32;
    private static final int BAND_HEIGHT = 28;
    private static final int BUTTON_PAD = 3;
    private static final String ENTER = "tanager.pane.enter";
    private static final String ESCAPE = "tanager.pane.escape";
    private static final String TAB = "tanager.pane.tab";
    private static final String WARNING_MARK = "⚠";

    /** 창을 품은 쪽(메인 창). */
    public interface Host {

        /** 탭 이름 · 상태줄 왼쪽 글자가 바뀌었다. */
        void viewChanged(String tabTitle, String status);

        /** 사용자에게 알릴 것(상태줄 오른쪽). */
        void notify(List<String> messages);

        /** 이 창이 닫혔다(Esc). */
        void removed();

        /** 이 창 안으로 키보드 포커스가 들어왔다(클릭 · Tab 이동, #89). */
        void focusGained();

        /** 머리줄 버튼 · 더블클릭(줌)으로 이 창에 {@code action} 을 하려 한다(#149). 포커스를 먼저 이 창으로 옮기는 일은 받는 쪽 몫이다. */
        void layoutRequested(LayoutAction action);

        /**
         * 머리줄 우클릭(#149). {@code invoker} 의 ({@code x}, {@code y})에 이 창의 메뉴를 띄운다.
         *
         * @param shell 이 창의 셸. 모르면(선택 화면 · 기동 중) 비어 있다
         */
        void menuRequested(Optional<ShellProfile> shell, Component invoker, int x, int y);
    }

    private final PaneId id;
    private final Executor ui;
    private final Executor background;
    private final StartSessionUseCase sessions;
    private final ListProfilesUseCase profiles;
    private final TerminalSettings settings;
    private final Host host;

    private final JLabel header = new JLabel();
    private final JLabel warning = new JLabel(WARNING_MARK);
    private final JPanel headerBar = new JPanel(new BorderLayout());
    // 머리줄 오른쪽 버튼(#149): 오른쪽 분할 · 아래 분할 · 줌 · 닫기
    private final Map<LayoutAction, JButton> headerButtons = new LinkedHashMap<>();
    private final RoundedPane root = new RoundedPane(headerBar);
    private final JPanel body = new JPanel(new BorderLayout());
    private final Notice notice = new Notice();
    private final ShellPickerPanel picker = new ShellPickerPanel(this::dispatch);
    private final PropertyChangeListener focusListener = event -> updateFocus();

    private PaneState state = new PaneState.Launching(new Attempt.Fresh());
    private TanagerTermWidget widget;
    private boolean widgetConnected;
    // 지금 본문에 놓인 것(#143). 처음에는 아무것도 없다
    private Optional<Body> laidOut = Optional.empty();
    private Optional<TanagerTermWidget> laidOutWidget = Optional.empty();
    private boolean disposed;
    // 진행 중인 기동이 내놓을 스트림. 기동 중에 앱이 끝나도 그 스트림을 닫고 종료를 기다리려고 들고 있다
    private CompletableFuture<Optional<TerminalStream>> launching = CompletableFuture.completedFuture(Optional.empty());

    /** @param id 이 창의 식별자. 트리의 세션 번호 · cwd 추적이 이 창에 매인다 */
    public PaneController(
            PaneId id,
            Executor ui,
            Executor background,
            StartSessionUseCase sessions,
            ListProfilesUseCase profiles,
            TerminalSettings settings,
            Host host) {
        this.id = Objects.requireNonNull(id, "id");
        this.ui = Objects.requireNonNull(ui, "ui");
        this.background = Objects.requireNonNull(background, "background");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.host = Objects.requireNonNull(host, "host");
        this.widget = new TanagerTermWidget(settings);
        layoutPane();
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("focusOwner", focusListener);
    }

    /** 메인 창에 넣을 컴포넌트. */
    public JComponent component() {
        return root;
    }

    /**
     * 터미널 칸 밖에 쓰는 픽셀 — 테두리 · 머리줄 · 스크롤바(#150, 창 최소 크기). 창 크기에서 터미널 패널 크기를 뺀다. 패널은 칸에 딱 맞지 않아
     * 칸 하나 미만만큼 크게 잡힐 수 있다(최소 크기 쪽으로 안전하다). 아직 배치되지 않았으면 0.
     */
    public Dimension chrome() {
        Dimension pane = root.getSize();
        Dimension cells = widget.getTerminalPanel().getSize();
        if (cells.width == 0 || cells.height == 0) {
            return new Dimension(0, 0);
        }
        return new Dimension(Math.max(0, pane.width - cells.width), Math.max(0, pane.height - cells.height));
    }

    /** 기억한 셸을 띄운다. 창이 화면에 놓인 뒤 부른다(위젯 크기로 PTY 크기를 정한다). */
    public void start() {
        start(PaneSpec.remembered(id));
    }

    /** 명세대로 첫 셸을 띄운다(트리에서 연 창, #89). 창이 화면에 놓인 뒤 부른다. */
    public void start(PaneSpec spec) {
        Objects.requireNonNull(spec, "spec");
        render();
        launch(new Attempt.Fresh(spec.profile(), spec.dir()));
    }

    /** 셸이 실행 중이다(앱 창 닫기 확인, #89). */
    public boolean running() {
        return state instanceof PaneState.Running;
    }

    /**
     * 앱 종료: 셸을 닫고 위젯을 정리한다. 돌려주는 단계는 셸 프로세스가 끝나면 완료된다(기다릴지는 부른 쪽이 정한다). 기동 중이면
     * 기동이 끝나기를 기다렸다가 뜬 스트림을 닫고 그 종료까지 기다린다 — EDT 를 거치지 않으므로 창이 사라진 뒤에도 완료된다.
     */
    public CompletionStage<?> shutdown() {
        CompletionStage<?> exited = switch (state) {
            case PaneState.Running running -> running.session().stream().onExit();
            case PaneState.Launching _ -> launching.thenCompose(PaneController::closeAndAwait);
            case PaneState.Exited _, PaneState.Failed _, PaneState.Errored _, PaneState.Picking _, PaneState.Closed _ ->
                CompletableFuture.completedFuture(0);
        };
        dispatch(new PaneEvent.CloseRequested());
        dispose();
        return exited;
    }

    /** 사이드바의 재시작(#92): 끝난 창이면 Enter 와 같이 같은 경로에서 다시 띄운다. 다른 상태면 아무것도 하지 않는다. EDT 에서 부른다. */
    public void restart() {
        if (state instanceof PaneState.Exited) {
            dispatch(new PaneEvent.EnterPressed());
        }
    }

    /** 지금 상태(테스트용). */
    PaneState state() {
        return state;
    }

    /** 지금 놓인 위젯(테스트용). */
    TanagerTermWidget widget() {
        return widget;
    }

    /**
     * 셸 통합이 이 창의 셸이 지금 있는 경로를 알렸다(#82). 머리줄 · 상태줄이 그 경로를 보인다. EDT 에서 부른다.
     */
    public void cwdReported(WorkspacePath path) {
        dispatch(new PaneEvent.CwdReported(path));
    }

    /** 터미널(또는 지금 보이는 종료 띠 · 선택 화면)로 포커스를 돌린다. 사이드바 검색칸의 Esc(#85). EDT 에서 부른다. */
    public void focus() {
        switch (state) {
            case PaneState.Exited _, PaneState.Failed _, PaneState.Errored _ -> notice.requestFocusInWindow();
            case PaneState.Picking _ -> picker.requestFocusInWindow();
            case PaneState.Launching _, PaneState.Running _, PaneState.Closed _ -> widget.requestFocusInWindow();
        }
    }

    /** 머리줄 글자(테스트용). */
    JLabel header() {
        return header;
    }

    /** 머리줄 버튼(테스트용). */
    JButton headerButton(LayoutAction action) {
        return Optional.ofNullable(headerButtons.get(action)).orElseThrow();
    }

    // 메뉴의 "다른 프로파일로 분할" 후보를 정하려고 이 창의 셸을 알린다
    private Optional<ShellProfile> shell() {
        return switch (state) {
            case PaneState.Running running ->
                Optional.of(running.session().session().profile());
            case PaneState.Exited exited -> Optional.of(exited.session().profile());
            case PaneState.Launching _,
                    PaneState.Failed _,
                    PaneState.Errored _,
                    PaneState.Picking _,
                    PaneState.Closed _ -> Optional.empty();
        };
    }

    /** 셸 선택 화면(테스트용 — 키 바인딩을 실행하려고). */
    JComponent picker() {
        return picker;
    }

    /** 종료 띠 · 실패 화면(테스트용 — 키 바인딩을 실행하려고). */
    JComponent notice() {
        return notice;
    }

    private static CompletionStage<Integer> closeAndAwait(Optional<TerminalStream> started) {
        return started.map(stream -> {
                    stream.close();
                    return stream.onExit();
                })
                .orElse(CompletableFuture.completedFuture(0));
    }

    private void dispatch(PaneEvent event) {
        Transition transition = state.on(event);
        state = transition.next();
        render();
        for (PaneEffect effect : transition.effects()) {
            run(effect);
        }
    }

    private void run(PaneEffect effect) {
        switch (effect) {
            case PaneEffect.Launch(Attempt attempt) -> launch(attempt);
            case PaneEffect.Connect(StartedSession session) -> connect(session);
            case PaneEffect.ShowNotices(List<StartNotice> notices) -> host.notify(describe(notices));
            case PaneEffect.CloseStream(TerminalStream closing) -> closing.close();
            case PaneEffect.RemovePane _ -> {
                dispose();
                host.removed();
            }
            case PaneEffect.ListShells _ -> listShells();
        }
    }

    private void listShells() {
        background.execute(() -> {
            try {
                List<ShellProfile> shells = profiles.list();
                ui.execute(() -> dispatch(new PaneEvent.ShellsListed(shells)));
            } catch (RuntimeException e) {
                // 목록을 못 읽으면 선택 화면을 열지 않는다 — 지금 화면에서 Enter · Esc 는 그대로 쓸 수 있다
                LOG.log(System.Logger.Level.WARNING, "셸 목록을 읽지 못했다", e);
            }
        });
    }

    private void launch(Attempt attempt) {
        if (widgetConnected) {
            // 끝난 위젯은 다시 쓸 수 없다(ADR-0011) — 새 위젯을 놓고, 크기는 배치가 끝난 다음에 읽는다
            widget.close();
            widget = new TanagerTermWidget(settings);
            widgetConnected = false;
            render();
        }
        CompletableFuture<Optional<TerminalStream>> pending = new CompletableFuture<>();
        launching = pending;
        ui.execute(() -> {
            if (state instanceof PaneState.Closed) {
                // 크기를 읽기 전에 닫혔다 — 셸을 띄우지 않는다
                pending.complete(Optional.empty());
                return;
            }
            TerminalSize size = widget.currentSize().orElse(TerminalSize.DEFAULT);
            background.execute(() -> {
                PaneEvent event = attemptStart(attempt, size);
                pending.complete(streamOf(event));
                ui.execute(() -> dispatch(event));
            });
        });
    }

    // 유스케이스는 실패를 값으로 돌려주기로 돼 있다. 그래도 예외가 나면(버그) 창이 "시작하는 중"에 멈추지 않게 사건으로 바꾼다
    private PaneEvent attemptStart(Attempt attempt, TerminalSize size) {
        try {
            return start(attempt, size).<PaneEvent>fold(PaneEvent.Started::new, PaneEvent.StartFailed::new);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "셸 기동이 예외로 끝났다", e);
            return new PaneEvent.StartErrored(e.toString());
        }
    }

    private static Optional<TerminalStream> streamOf(PaneEvent event) {
        return event instanceof PaneEvent.Started(StartedSession session)
                ? Optional.of(session.stream())
                : Optional.empty();
    }

    private Result<StartedSession, StartFailure> start(Attempt attempt, TerminalSize size) {
        return switch (attempt) {
            case Attempt.Fresh(var profile, var dir) ->
                sessions.start(new StartRequest(id, profile, false, dir.map(WorkingDir.At::new), size));
            case Attempt.Restart(var exited) -> sessions.restart(id, exited, size);
            case Attempt.Chosen(ShellProfile profile, boolean remember) ->
                sessions.start(StartRequest.chosen(id, profile.id(), remember, size));
        };
    }

    private void connect(StartedSession session) {
        StreamTtyConnector connector = StreamTtyConnector.attach(
                session.stream(), session.session().profile().name());
        widgetConnected = true;
        // setTtyConnector 가 현재 위젯 크기로 PTY 크기를 한 번 맞춘다(TerminalPanel.setTerminalStarter → postResize)
        widget.setTtyConnector(connector);
        widget.getTerminalPanel().setFocusable(true);
        widget.start();
        session.stream().start();
        session.exited().whenComplete((exited, error) -> {
            if (exited != null) {
                ui.execute(() -> dispatch(new PaneEvent.ShellExited(exited)));
            } else {
                LOG.log(System.Logger.Level.ERROR, "셸 종료를 받지 못했다", error);
            }
        });
        widget.requestFocusInWindow();
    }

    private void render() {
        if (state instanceof PaneState.Closed) {
            // 닫힌 창은 곧 사라진다 — 다시 그리지 않는다
            return;
        }
        PaneView view = PaneView.of(state);
        renderHeader(view);
        host.viewChanged(PaneView.tabTitle(state), view.status());
        Body next = switch (state) {
            case PaneState.Exited _ -> Body.EXITED;
            case PaneState.Failed _, PaneState.Errored _ -> Body.NOTICE;
            case PaneState.Picking _ -> Body.PICKER;
            case PaneState.Launching _, PaneState.Running _, PaneState.Closed _ -> Body.TERMINAL;
        };
        switch (state) {
            case PaneState.Exited _ ->
                notice.band(view.overlay().orElse("") + " · " + view.hint().orElse(""));
            case PaneState.Failed _, PaneState.Errored _ ->
                notice.screen(view.overlay().orElse(""), view.hint().orElse(""));
            case PaneState.Picking picking -> picker.render(PickerView.of(picking));
            case PaneState.Launching _, PaneState.Running _, PaneState.Closed _ -> {}
        }
        // 놓을 것이 그대로면 떼었다 붙이지 않는다. 포커스를 가진 터미널이 잠깐이라도 빠지면 Swing 이 포커스를 창의 첫 컴포넌트(사이드바
        // 검색칸)로 옮긴다 — 셸이 경로를 알릴 때마다 입력이 검색칸으로 갔다(#143)
        if (laidOut.equals(Optional.of(next)) && laidOutWidget.equals(Optional.of(widget))) {
            return;
        }
        laidOut = Optional.of(next);
        laidOutWidget = Optional.of(widget);
        body.removeAll();
        switch (next) {
            case EXITED -> {
                // 끝난 터미널을 눌러도 포커스가 띠에 남아 Enter · Esc 가 창으로 온다(JediTerm 패널은 키 바인딩을 거치지 않는다)
                widget.getTerminalPanel().setFocusable(false);
                body.add(widget, BorderLayout.CENTER);
                body.add(notice, BorderLayout.SOUTH);
            }
            case NOTICE -> body.add(notice, BorderLayout.CENTER);
            // 위젯은 빼기만 하고 닫지 않는다 — Esc 로 돌아오면 끝난 출력과 함께 다시 놓는다
            case PICKER -> body.add(picker, BorderLayout.CENTER);
            case TERMINAL -> {
                if (state instanceof PaneState.Launching) {
                    // 연결 전에는 포커스를 받지 않는다 — 선택 화면에서 누른 키(숫자 · Enter)의 글자가 새 터미널로 새지 않게.
                    // connect 에서 다시 켠다
                    widget.getTerminalPanel().setFocusable(false);
                }
                body.add(widget, BorderLayout.CENTER);
            }
        }
        body.revalidate();
        body.repaint();
        // 배치가 끝난 뒤에 포커스를 옮긴다 — 본문이 바뀔 때만(다시 그리기가 다른 곳의 포커스를 빼앗지 않게)
        switch (next) {
            case EXITED, NOTICE -> ui.execute(notice::requestFocusInWindow);
            case PICKER -> ui.execute(picker::requestFocusInWindow);
            case TERMINAL -> {}
        }
    }

    /** 본문에 놓인 것의 종류. 같은 종류 · 같은 위젯이면 다시 놓지 않는다. */
    private enum Body {
        TERMINAL,
        EXITED,
        NOTICE,
        PICKER
    }

    // 경로는 폭에 맞게 가운데를 줄이고(PathEllipsis) 전체 경로는 툴팁으로 보인다. 아직 배치 전(폭 0)이면 그대로 둔다
    private void renderHeader(PaneView view) {
        header.setToolTipText(view.path().orElse(null));
        header.setText(fitted(view));
        warning.setVisible(view.warning().isPresent());
        warning.setToolTipText(view.warning().orElse(null));
    }

    private String fitted(PaneView view) {
        String full = view.header();
        Insets insets = header.getInsets();
        int available = header.getWidth() - insets.left - insets.right;
        if (view.path().isEmpty() || available <= 0) {
            return full;
        }
        FontMetrics metrics = header.getFontMetrics(header.getFont());
        if (metrics.stringWidth(full) <= available) {
            return full;
        }
        String path = view.path().orElseThrow();
        String prefix = full.substring(0, full.length() - path.length());
        for (int width = path.codePointCount(0, path.length()) - 1; width > 1; width--) {
            String candidate = prefix + PathEllipsis.fit(path, width);
            if (metrics.stringWidth(candidate) <= available) {
                return candidate;
            }
        }
        return prefix + PathEllipsis.fit(path, 1);
    }

    private void layoutPane() {
        headerBar.setOpaque(false);
        headerBar.setPreferredSize(new Dimension(0, HEADER_HEIGHT));
        headerBar.setBorder(new EmptyBorder(0, 10, 0, 10));
        header.setForeground(ThemeColors.get(ColorToken.MUTED));
        warning.setForeground(ThemeColors.get(ColorToken.WARN));
        warning.setVisible(false);
        headerBar.add(header, BorderLayout.CENTER);
        headerBar.add(headerRight(), BorderLayout.EAST);
        // 라벨은 툴팁 때문에 마우스 사건을 직접 받는다 — 머리줄 어디를 눌러도 같게 셋 모두에 단다
        MouseAdapter headerMouse = headerMouse();
        headerBar.addMouseListener(headerMouse);
        header.addMouseListener(headerMouse);
        warning.addMouseListener(headerMouse);
        // 폭이 바뀌면 경로 줄임을 다시 계산한다
        header.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (!(state instanceof PaneState.Closed)) {
                    renderHeader(PaneView.of(state));
                }
            }
        });
        body.setOpaque(false);
        root.add(headerBar, BorderLayout.NORTH);
        root.add(body, BorderLayout.CENTER);

        bind(KeyStroke.getKeyStroke("ENTER"), ENTER, () -> dispatch(new PaneEvent.EnterPressed()));
        bind(KeyStroke.getKeyStroke("ESCAPE"), ESCAPE, () -> dispatch(new PaneEvent.EscPressed()));
        // Tab 은 포커스를 가진 띠에만 묶는다 — 실행 중 셸의 Tab(자동 완성)을 가로채지 않는다
        notice.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("TAB"), TAB);
        notice.getActionMap().put(TAB, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dispatch(new PaneEvent.TabPressed());
            }
        });
        notice.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                notice.requestFocusInWindow();
            }
        });
    }

    // 경고 표시 · 버튼. 버튼은 포커스를 받지 않는다 — 눌러도 키보드 포커스가 터미널에 남는다
    private JPanel headerRight() {
        // GridBagLayout 은 한 줄로 놓고 세로 가운데에 맞춘다
        JPanel right = new JPanel(new GridBagLayout());
        right.setOpaque(false);
        right.add(warning);
        addButton(right, new LayoutAction.Split(Orientation.ROW), HeaderIcon.Shape.SPLIT_RIGHT, Msg.LAYOUT_SPLIT_RIGHT);
        addButton(
                right, new LayoutAction.Split(Orientation.COLUMN), HeaderIcon.Shape.SPLIT_DOWN, Msg.LAYOUT_SPLIT_DOWN);
        addButton(right, new LayoutAction.Zoom(), HeaderIcon.Shape.ZOOM, Msg.LAYOUT_ZOOM);
        addButton(right, new LayoutAction.Close(), HeaderIcon.Shape.CLOSE, Msg.LAYOUT_CLOSE);
        return right;
    }

    private void addButton(JPanel bar, LayoutAction action, HeaderIcon.Shape shape, Msg name) {
        JButton button = new JButton(new HeaderIcon(shape, ColorToken.MUTED));
        button.setRolloverIcon(new HeaderIcon(shape, ColorToken.TEXT));
        button.setToolTipText(KeyHelp.withKey(name.text(), action));
        button.setFocusable(false);
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setRolloverEnabled(true);
        button.setBorder(new EmptyBorder(BUTTON_PAD, BUTTON_PAD, BUTTON_PAD, BUTTON_PAD));
        button.addActionListener(e -> host.layoutRequested(action));
        headerButtons.put(action, button);
        bar.add(button);
    }

    // 클릭 = 이 창에 포커스, 더블클릭 = 줌, 우클릭 = 메뉴(#149). 팝업 트리거는 OS 마다 누를 때 · 뗄 때가 달라 둘 다 본다
    private MouseAdapter headerMouse() {
        return new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    menu(e);
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    focus();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    menu(e);
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
                    host.layoutRequested(new LayoutAction.Zoom());
                }
            }

            private void menu(MouseEvent e) {
                if (!(state instanceof PaneState.Closed)) {
                    host.menuRequested(shell(), e.getComponent(), e.getX(), e.getY());
                }
            }
        };
    }

    // 창(root) 안 어디에 포커스가 있든 받는다. 실행 중에는 터미널 패널이 키를 직접 먹고, 상태 기계도 무시한다
    private void bind(KeyStroke key, String name, Runnable action) {
        root.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(key, name);
        root.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    private void updateFocus() {
        Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        focusChanged(owner != null && SwingUtilities.isDescendingFrom(owner, root));
    }

    /** 창 안에 키보드 포커스가 있는지 바뀌었다 — 테두리를 다시 그리고, 들어왔으면 Host 에 알린다. */
    void focusChanged(boolean focused) {
        if (focused != root.focused) {
            root.focused = focused;
            root.repaint();
            if (focused) {
                host.focusGained();
            }
        }
    }

    private void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removePropertyChangeListener("focusOwner", focusListener);
        widget.close();
    }

    private static List<String> describe(List<StartNotice> notices) {
        List<String> messages = new ArrayList<>();
        for (StartNotice notice : notices) {
            messages.add(NoticeText.DEFAULT.of(notice));
        }
        return messages;
    }

    /** 종료 띠(터미널 아래 한 줄) 또는 실패 화면(본문 전체). Enter · Tab · Esc 를 받으려고 포커스를 가진다. */
    private static final class Notice extends JPanel {
        private final JTextArea message = new JTextArea();
        private final JLabel hint = new JLabel();

        Notice() {
            super(new BorderLayout(0, 8));
            setFocusable(true);
            // Tab 이 포커스 이동으로 먹히지 않고 키 바인딩에 오게 한다
            setFocusTraversalKeysEnabled(false);
            setBackground(ThemeColors.get(ColorToken.HEADER));
            message.setEditable(false);
            message.setFocusable(false);
            message.setOpaque(false);
            message.setLineWrap(true);
            message.setWrapStyleWord(true);
            hint.setForeground(ThemeColors.get(ColorToken.MUTED));
        }

        void band(String text) {
            removeAll();
            setOpaque(true);
            setPreferredSize(new Dimension(0, BAND_HEIGHT));
            setBorder(new EmptyBorder(0, 10, 0, 10));
            hint.setText(text);
            hint.setForeground(ThemeColors.get(ColorToken.TEXT));
            add(hint, BorderLayout.CENTER);
        }

        void screen(String text, String keys) {
            removeAll();
            setOpaque(false);
            setPreferredSize(null);
            setBorder(new EmptyBorder(24, 24, 24, 24));
            message.setText(text);
            message.setForeground(ThemeColors.get(ColorToken.WARN));
            hint.setText(keys);
            hint.setForeground(ThemeColors.get(ColorToken.MUTED));
            add(message, BorderLayout.NORTH);
            add(hint, BorderLayout.CENTER);
        }
    }

    /**
     * 둥근 모서리 창. 자식은 반지름만큼 안쪽에 놓아 사각형 자식(터미널)이 모서리 밖으로 나가지 않게 하고, 테두리는 포커스가 안에
     * 있으면 2px 강조색, 아니면 1px 경계색(앱이 비활성이어도)이다.
     */
    private static final class RoundedPane extends JPanel {
        private final int arc = UIManager.getInt("Tanager.pane.arc");
        private final int focusWidth = Math.max(1, UIManager.getInt("Tanager.focusBorder.width"));
        private final JComponent header;
        private boolean focused;

        RoundedPane(JComponent header) {
            super(new BorderLayout());
            this.header = header;
            setOpaque(false);
            int inset = Math.max(focusWidth, (arc + 1) / 2);
            setBorder(new EmptyBorder(inset, inset, inset, inset));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                RoundRectangle2D shape = new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), arc, arc);
                g2.setColor(ThemeColors.get(ColorToken.TERM));
                g2.fill(shape);
                // 머리줄은 투명하다 — 둥근 모양 안에서 머리줄 높이만큼 header 색을 칠한다
                g2.clip(shape);
                g2.setColor(ThemeColors.get(ColorToken.HEADER));
                g2.fillRect(0, 0, getWidth(), header.getY() + header.getHeight());
            } finally {
                g2.dispose();
            }
        }

        @Override
        protected void paintBorder(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                float width = focused ? focusWidth : 1f;
                g2.setStroke(new BasicStroke(width));
                g2.setColor(ThemeColors.get(focused ? ColorToken.ACCENT : ColorToken.BORDER));
                float half = width / 2f;
                g2.draw(new RoundRectangle2D.Float(half, half, getWidth() - width, getHeight() - width, arc, arc));
            } finally {
                g2.dispose();
            }
        }
    }
}
