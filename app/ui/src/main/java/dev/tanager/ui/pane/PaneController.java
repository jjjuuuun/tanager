package dev.tanager.ui.pane;

import dev.tanager.application.port.in.StartFailure;
import dev.tanager.application.port.in.StartNotice;
import dev.tanager.application.port.in.StartRequest;
import dev.tanager.application.port.in.StartSessionUseCase;
import dev.tanager.application.port.in.StartedSession;
import dev.tanager.application.port.out.TerminalSize;
import dev.tanager.application.port.out.TerminalStream;
import dev.tanager.domain.shared.Result;
import dev.tanager.ui.terminal.StreamTtyConnector;
import dev.tanager.ui.terminal.TanagerTermWidget;
import dev.tanager.ui.terminal.TerminalSettings;
import dev.tanager.ui.theme.ColorToken;
import dev.tanager.ui.theme.ThemeColors;
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

    /** 창을 품은 쪽(메인 창). */
    public interface Host {

        /** 탭 이름 · 상태줄 왼쪽 글자가 바뀌었다. */
        void viewChanged(String tabTitle, String status);

        /** 사용자에게 알릴 것(상태줄 오른쪽). */
        void notify(List<String> messages);

        /** 이 창이 닫혔다(Esc). */
        void removed();
    }

    private final Executor ui;
    private final Executor background;
    private final StartSessionUseCase sessions;
    private final TerminalSettings settings;
    private final Host host;

    private final JLabel header = new JLabel();
    private final RoundedPane root = new RoundedPane(header);
    private final JPanel body = new JPanel(new BorderLayout());
    private final Notice notice = new Notice();
    private final PropertyChangeListener focusListener = event -> updateFocus();

    private PaneState state = new PaneState.Launching(new Attempt.Fresh());
    private TanagerTermWidget widget;
    private boolean widgetConnected;
    private boolean disposed;
    private Optional<TerminalStream> stream = Optional.empty();

    public PaneController(
            Executor ui, Executor background, StartSessionUseCase sessions, TerminalSettings settings, Host host) {
        this.ui = Objects.requireNonNull(ui, "ui");
        this.background = Objects.requireNonNull(background, "background");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
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
     * 앱 종료: 셸을 닫고 위젯을 정리한다. 돌려주는 단계는 셸 프로세스가 끝나면 완료된다(기다릴지는 부른 쪽이 정한다).
     */
    public CompletionStage<?> shutdown() {
        CompletionStage<?> exited =
                stream.<CompletionStage<?>>map(TerminalStream::onExit).orElse(CompletableFuture.completedFuture(0));
        dispatch(new PaneEvent.CloseRequested());
        dispose();
        return exited;
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
        }
    }

    private void launch(Attempt attempt) {
        if (widgetConnected) {
            // 끝난 위젯은 다시 쓸 수 없다(ADR-0011) — 새 위젯을 놓고, 크기는 배치가 끝난 다음에 읽는다
            widget.close();
            widget = new TanagerTermWidget(settings);
            widgetConnected = false;
            render();
        }
        ui.execute(() -> {
            TerminalSize size = widget.currentSize().orElse(TerminalSize.DEFAULT);
            background.execute(() -> deliver(start(attempt, size)));
        });
    }

    private Result<StartedSession, StartFailure> start(Attempt attempt, TerminalSize size) {
        return switch (attempt) {
            case Attempt.Fresh _ -> sessions.start(new StartRequest(Optional.empty(), Optional.empty(), size));
            case Attempt.Restart(var exited) -> sessions.restart(exited, size);
        };
    }

    private void deliver(Result<StartedSession, StartFailure> result) {
        PaneEvent event = result.<PaneEvent>fold(PaneEvent.Started::new, PaneEvent.StartFailed::new);
        ui.execute(() -> dispatch(event));
    }

    private void connect(StartedSession session) {
        StreamTtyConnector connector = StreamTtyConnector.attach(
                session.stream(), session.session().profile().name());
        stream = Optional.of(session.stream());
        widgetConnected = true;
        // setTtyConnector 가 현재 위젯 크기로 PTY 크기를 한 번 맞춘다(TerminalPanel.setTerminalStarter → postResize)
        widget.setTtyConnector(connector);
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
        PaneView view = PaneView.of(state);
        header.setText(view.header());
        host.viewChanged(PaneView.tabTitle(state), view.status());
        body.removeAll();
        switch (state) {
            case PaneState.Exited _ -> {
                body.add(widget, BorderLayout.CENTER);
                notice.band(view.overlay().orElse("") + " · " + view.hint().orElse(""));
                body.add(notice, BorderLayout.SOUTH);
            }
            case PaneState.Failed _ -> {
                notice.screen(view.overlay().orElse(""), view.hint().orElse(""));
                body.add(notice, BorderLayout.CENTER);
            }
            case PaneState.Launching _, PaneState.Running _, PaneState.Closed _ ->
                body.add(widget, BorderLayout.CENTER);
        }
        body.revalidate();
        body.repaint();
        if (state instanceof PaneState.Exited || state instanceof PaneState.Failed) {
            // 배치가 끝난 뒤에 포커스를 옮긴다
            ui.execute(notice::requestFocusInWindow);
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
        notice.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                notice.requestFocusInWindow();
            }
        });
    }

    private void bind(KeyStroke key, String name, Runnable action) {
        notice.getInputMap(JComponent.WHEN_FOCUSED).put(key, name);
        notice.getActionMap().put(name, new AbstractAction() {
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

    /** 종료 띠(터미널 아래 한 줄) 또는 실패 화면(본문 전체). Enter · Esc 를 받으려고 포커스를 가진다. */
    private static final class Notice extends JPanel {
        private final JTextArea message = new JTextArea();
        private final JLabel hint = new JLabel();

        Notice() {
            super(new BorderLayout(0, 8));
            setFocusable(true);
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
