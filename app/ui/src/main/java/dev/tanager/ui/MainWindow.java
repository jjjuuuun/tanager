package dev.tanager.ui;

import com.formdev.flatlaf.FlatClientProperties;
import dev.tanager.application.port.in.ListProfilesUseCase;
import dev.tanager.application.port.in.StartSessionUseCase;
import dev.tanager.domain.shared.PaneId;
import dev.tanager.ui.pane.PaneController;
import dev.tanager.ui.terminal.TerminalSettings;
import dev.tanager.ui.theme.ColorToken;
import dev.tanager.ui.theme.ThemeColors;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuBar;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

/**
 * 메인 창(시안 D 의 M2 부분): 제목 표시줄 안 탭 · 터미널 창 하나 · 상태줄. EDT 에서만 만진다.
 *
 * <p>앱 종료 순서(ADR-0011): 셸 닫기(기동 중이면 기동이 끝난 뒤) → 창 dispose(바로 사라진다) → 비데몬 스레드에서 앱 종료 훅
 * (남은 명령 처리 · 미저장 이력 저장 최대 2초) → 셸 종료를 닫은 때부터 최대 4초까지 기다림 → {@code System.exit(0)}.
 */
final class MainWindow implements PaneController.Host {
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
    // M3 는 창이 하나다. 분할(M4)부터는 레이아웃 트리의 Leaf 가 창 ID 를 준다
    private static final PaneId ONLY_PANE = new PaneId("p1");

    private final JFrame frame = new JFrame("Tanager");
    private final JLabel tab = new JLabel("Tanager");
    private final JLabel statusLeft = new JLabel();
    private final JLabel statusRight = new JLabel();
    private Notices notices = new Notices(List.of());
    private final PaneController pane;
    private final Runnable onShutdown;
    private boolean closing;

    MainWindow(
            StartSessionUseCase sessions,
            ListProfilesUseCase profiles,
            Runnable onShutdown,
            TerminalSettings settings,
            Executor background) {
        this.onShutdown = Objects.requireNonNull(onShutdown, "onShutdown");
        Executor ui = SwingUtilities::invokeLater;
        this.pane = new PaneController(ONLY_PANE, ui, background, sessions, profiles, settings, this);
        frame.setJMenuBar(titleBarTabs());
        frame.getContentPane().add(paneArea(), BorderLayout.CENTER);
        frame.getContentPane().add(statusBar(), BorderLayout.SOUTH);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                exit();
            }
        });
        frame.setSize(WIDTH, HEIGHT);
        frame.setMinimumSize(new Dimension(MIN_WIDTH, MIN_HEIGHT));
        frame.setLocationRelativeTo(null);
    }

    /** 창을 띄우고(아직 연결하지 않은 위젯이 놓인다) 첫 셸을 띄운다. */
    void show(List<String> warnings) {
        frame.setVisible(true);
        if (!warnings.isEmpty()) {
            notify(warnings);
        }
        pane.start();
    }

    @Override
    public void viewChanged(String tabTitle, String status) {
        tab.setText(tabTitle);
        statusLeft.setText(status);
    }

    @Override
    public void notify(List<String> messages) {
        for (String message : messages) {
            LOG.log(System.Logger.Level.WARNING, "알림: {0}", message);
        }
        notices = notices.add(messages);
        notices.summary().ifPresent(summary -> {
            statusRight.setText(summary);
            statusRight.setToolTipText(tooltip(notices.messages()));
        });
    }

    /** M2 는 창이 하나라 마지막 창이 닫히면 확인 없이 앱을 끝낸다(ADR-0011). */
    @Override
    public void removed() {
        exit();
    }

    private void exit() {
        if (closing) {
            return;
        }
        closing = true;
        CompletionStage<?> exited = pane.shutdown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(EXIT_WAIT_SECONDS);
        frame.dispose();
        // 비데몬 스레드: 마지막 창이 사라져도 이 대기가 끝나기 전에 JVM 이 멈추지 않는다. 셸이 끝나는 동안 이력을 저장한다
        Thread.ofPlatform().daemon(false).name("tanager-exit").start(() -> {
            runQuietly(onShutdown);
            awaitQuietly(exited, deadline);
            System.exit(0);
        });
    }

    private static void runQuietly(Runnable hook) {
        try {
            hook.run();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "앱 종료 훅이 실패했다", e);
        }
    }

    private static void awaitQuietly(CompletionStage<?> exited, long deadline) {
        try {
            long left = Math.max(0, deadline - System.nanoTime());
            exited.toCompletableFuture().get(left, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            LOG.log(System.Logger.Level.WARNING, "셸이 {0}초 안에 끝나지 않아 그대로 종료한다", EXIT_WAIT_SECONDS);
        } catch (ExecutionException e) {
            LOG.log(System.Logger.Level.WARNING, "셸 종료를 기다리다 오류", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
        area.add(pane.component(), BorderLayout.CENTER);
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
