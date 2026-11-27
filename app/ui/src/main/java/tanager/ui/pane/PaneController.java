package tanager.ui.pane;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.KeyboardFocusManager;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import javax.swing.AbstractAction;
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
import tanager.domain.session.ShellProfile;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.Result;
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
    private static final String ENTER = "tanager.pane.enter";
    private static final String ESCAPE = "tanager.pane.escape";
    private static final String TAB = "tanager.pane.tab";

    /** 창을 품은 쪽(메인 창). */
    public interface Host {

        /** 탭 이름 · 상태줄 왼쪽 글자가 바뀌었다. */
        void viewChanged(String tabTitle, String status);

        /** 사용자에게 알릴 것(상태줄 오른쪽). */
        void notify(List<String> messages);

        /** 이 창이 닫혔다(Esc). */
        void removed();
    }

    private final PaneId id;
    private final Executor ui;
    private final Executor background;
    private final StartSessionUseCase sessions;
    private final ListProfilesUseCase profiles;
    private final TerminalSettings settings;
    private final Host host;

    private final JLabel header = new JLabel();
    private final RoundedPane root = new RoundedPane(header);
    private final JPanel body = new JPanel(new BorderLayout());
    private final Notice notice = new Notice();
    private final ShellPickerPanel picker = new ShellPickerPanel(this::dispatch);
    private final PropertyChangeListener focusListener = event -> updateFocus();

    private PaneState state = new PaneState.Launching(new Attempt.Fresh());
    private TanagerTermWidget widget;
    private boolean widgetConnected;
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

    /** 첫 셸을 띄운다. 창이 화면에 놓인 뒤 부른다(위젯 크기로 PTY 크기를 정한다). */
    public void start() {
        render();
        launch(new Attempt.Fresh());
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

    /** 지금 상태(테스트용). */
    PaneState state() {
        return state;
    }

    /** 지금 놓인 위젯(테스트용). */
    TanagerTermWidget widget() {
        return widget;
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
            case Attempt.Fresh _ -> sessions.start(StartRequest.remembered(id, size));
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
        header.setText(view.header());
        host.viewChanged(PaneView.tabTitle(state), view.status());
        body.removeAll();
        switch (state) {
            case PaneState.Exited _ -> {
                // 끝난 터미널을 눌러도 포커스가 띠에 남아 Enter · Esc 가 창으로 온다(JediTerm 패널은 키 바인딩을 거치지 않는다)
                widget.getTerminalPanel().setFocusable(false);
                body.add(widget, BorderLayout.CENTER);
                notice.band(view.overlay().orElse("") + " · " + view.hint().orElse(""));
                body.add(notice, BorderLayout.SOUTH);
            }
            case PaneState.Failed _, PaneState.Errored _ -> {
                notice.screen(view.overlay().orElse(""), view.hint().orElse(""));
                body.add(notice, BorderLayout.CENTER);
            }
            case PaneState.Picking picking -> {
                // 위젯은 빼기만 하고 닫지 않는다 — Esc 로 돌아오면 끝난 출력과 함께 다시 놓는다
                picker.render(PickerView.of(picking));
                body.add(picker, BorderLayout.CENTER);
            }
            case PaneState.Launching _ -> {
                // 연결 전에는 포커스를 받지 않는다 — 선택 화면에서 누른 키(숫자 · Enter)의 글자가 새 터미널로 새지 않게.
                // connect 에서 다시 켠다
                widget.getTerminalPanel().setFocusable(false);
                body.add(widget, BorderLayout.CENTER);
            }
            case PaneState.Running _, PaneState.Closed _ -> body.add(widget, BorderLayout.CENTER);
        }
        body.revalidate();
        body.repaint();
        // 배치가 끝난 뒤에 포커스를 옮긴다
        switch (state) {
            case PaneState.Exited _, PaneState.Failed _, PaneState.Errored _ ->
                ui.execute(notice::requestFocusInWindow);
            case PaneState.Picking _ -> ui.execute(picker::requestFocusInWindow);
            case PaneState.Launching _, PaneState.Running _, PaneState.Closed _ -> {}
        }
    }

    private void layoutPane() {
        header.setPreferredSize(new Dimension(0, HEADER_HEIGHT));
        header.setBorder(new EmptyBorder(0, 10, 0, 10));
        header.setForeground(ThemeColors.get(ColorToken.MUTED));
        body.setOpaque(false);
        root.add(header, BorderLayout.NORTH);
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
        boolean focused = owner != null && SwingUtilities.isDescendingFrom(owner, root);
        if (focused != root.focused) {
            root.focused = focused;
            root.repaint();
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
            messages.add(notice.describe());
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
