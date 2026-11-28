package tanager.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.event.KeyEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import javax.swing.JFrame;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import tanager.adapter.pty.Pty4jLauncher;
import tanager.application.port.out.LaunchCommand;
import tanager.application.port.out.TerminalSize;
import tanager.application.port.out.TerminalStream;
import tanager.domain.shared.PaneId;
import tanager.ui.font.TerminalFontSet;
import tanager.ui.font.TerminalFonts;
import tanager.ui.measure.Measurement;
import tanager.ui.terminal.StreamTtyConnector;
import tanager.ui.terminal.TanagerTermWidget;
import tanager.ui.terminal.TerminalSettings;
import tanager.ui.theme.JediTermColors;

/**
 * NFR-01 측정(#222): 실제 cmd(ConPTY) 창에 키를 보내 화면 반영까지를 측정 모드의 {@code InputLatencyProbe} 로 잰다. 앞 30초는 기준,
 * 뒤 30초는 옆 창이 WSL 에서 50MB 를 출력하는 중이다({@code head -c 50M /dev/urandom | base64}). 결과는 측정 모드 로그 두 줄이다.
 *
 * <p>목표(p95 30ms)를 판정하지 않는다 — 수치는 PR · NFR 기록에 옮긴다. 키는 OS 입력이 아니라 패널에 바로 넘긴다(사용자의 다른 창에 키가 가지
 * 않게, 창은 포커스를 받지 않는다). 그래서 OS 입력 대기열 시간은 빠진다. 화면 · WSL 이 있는 Windows 에서만.
 */
@Tag("perf")
@Tag("windows")
@Tag("wsl")
@EnabledOnOs(OS.WINDOWS)
class InputLatencyPerfTest {
    private static final System.Logger LOG = System.getLogger(InputLatencyPerfTest.class.getName());
    private static final Duration WINDOW = Duration.ofSeconds(35);
    private static final long TYPE_EVERY_MILLIS = 100;
    private static final TerminalSize SIZE = new TerminalSize(90, 40);

    @Test
    @DisplayName("기준 30초와 옆 창 50MB 출력 중 30초의 입력 반응을 측정 모드 로그로 남긴다")
    void inputLatency_idleThenNextToFlood() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "화면이 필요하다");
        // given
        List<String> lines = new CopyOnWriteArrayList<>();
        Logger measureLog = Logger.getLogger("tanager.ui.measure.ActiveMeasurement");
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                lines.add(record.getMessage());
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        measureLog.addHandler(capture);
        Pty4jLauncher launcher = new Pty4jLauncher();
        TerminalStream typing = launch(launcher, List.of("cmd.exe"));
        AtomicReference<Optional<TerminalStream>> flood = new AtomicReference<>(Optional.empty());
        StreamTtyConnector typingConnector = StreamTtyConnector.attach(typing, "typing");
        AtomicReference<JFrame> frame = new AtomicReference<>();
        AtomicReference<LookAndFeel> previous = new AtomicReference<>();
        AtomicReference<TanagerTermWidget> typed = new AtomicReference<>();
        AtomicReference<TanagerTermWidget> flooded = new AtomicReference<>();
        Measurement measurement = Measurement.of(true);
        ScheduledExecutorService typist = Executors.newSingleThreadScheduledExecutor(
                task -> Thread.ofPlatform().name("bench-typist").daemon(true).unstarted(task));
        try {
            SwingUtilities.invokeAndWait(() -> {
                previous.set(UIManager.getLookAndFeel());
                setLookAndFeel("tanager.ui.theme.SoftDarkLaf");
                TerminalFontSet fonts =
                        TerminalFonts.registerBundled().fold(set -> set, _ -> TerminalFontSet.fallback());
                TerminalSettings settings = new TerminalSettings(fonts, JediTermColors.current());
                typed.set(new TanagerTermWidget(settings, measurement.pane(new PaneId("p1"))));
                flooded.set(new TanagerTermWidget(settings));
                JFrame shown = new JFrame("tanager-bench");
                shown.setFocusableWindowState(false);
                shown.setAutoRequestFocus(false);
                shown.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                shown.getContentPane().setLayout(new GridLayout(1, 2, 8, 0));
                shown.getContentPane().add(typed.get());
                shown.getContentPane().add(flooded.get());
                shown.setBounds(40, 40, 1400, 800);
                shown.setVisible(true);
                frame.set(shown);
                typed.get().setTtyConnector(typingConnector);
                typed.get().start();
                measurement.start();
            });
            typing.start();
            await().atMost(Duration.ofSeconds(20))
                    .until(() ->
                            typed.get().getTerminalTextBuffer().getScreenLines().contains(">"));
            // 화면을 거치지 않은 셸 에코 왕복(ConPTY · cmd) — 위젯 몫과 셸 몫을 나눠 보려고
            LOG.log(System.Logger.Level.INFO, "bench: " + rawEcho(typing));
            typist.scheduleAtFixedRate(
                    () -> SwingUtilities.invokeLater(() -> type(typed.get())),
                    0,
                    TYPE_EVERY_MILLIS,
                    TimeUnit.MILLISECONDS);

            // when — 기준 구간 보고가 오면 옆 창에서 50MB 출력을 시작한다
            await().atMost(WINDOW).until(() -> latencyLines(lines).size() >= 1);
            TerminalStream output =
                    launch(launcher, List.of("wsl.exe", "--exec", "sh", "-c", "head -c 50M /dev/urandom | base64"));
            flood.set(Optional.of(output));
            AtomicLong floodBytes = new AtomicLong();
            output.subscribe(chunk -> floodBytes.addAndGet(chunk.length));
            StreamTtyConnector floodConnector = StreamTtyConnector.attach(output, "flood");
            SwingUtilities.invokeAndWait(() -> {
                flooded.get().setTtyConnector(floodConnector);
                flooded.get().start();
            });
            output.start();
            await().atMost(WINDOW).until(() -> latencyLines(lines).size() >= 2);
            lines.add("flood-bytes=" + floodBytes.get());

            // then
            for (String line : lines) {
                LOG.log(System.Logger.Level.INFO, "bench: " + line);
            }
            assertThat(latencyLines(lines)).hasSize(2);
        } finally {
            typist.shutdownNow();
            measureLog.removeHandler(capture);
            typingConnector.close();
            flood.get().ifPresent(TerminalStream::close);
            SwingUtilities.invokeAndWait(() -> {
                Optional.ofNullable(typed.get()).ifPresent(TanagerTermWidget::close);
                Optional.ofNullable(flooded.get()).ifPresent(TanagerTermWidget::close);
                Optional.ofNullable(frame.get()).ifPresent(JFrame::dispose);
                Optional.ofNullable(previous.get())
                        .ifPresent(laf -> setLookAndFeel(laf.getClass().getName()));
            });
        }
    }

    // 키 하나를 셸에 바로 쓰고 다음 출력 조각이 올 때까지. 30번, 사이 50ms
    private static String rawEcho(TerminalStream shell) {
        AtomicLong lastChunk = new AtomicLong();
        var subscription = shell.subscribe(_ -> lastChunk.set(System.nanoTime()));
        List<Long> millis = new ArrayList<>();
        try {
            for (int i = 0; i < 30; i++) {
                await().pollDelay(Duration.ofMillis(50)).until(() -> true);
                long sentAt = System.nanoTime();
                shell.write(new byte[] {'y'});
                await().pollInterval(Duration.ofMillis(1))
                        .atMost(Duration.ofSeconds(2))
                        .until(() -> lastChunk.get() > sentAt);
                millis.add(TimeUnit.NANOSECONDS.toMillis(lastChunk.get() - sentAt));
            }
            shell.write(new byte[] {(byte) KeyEvent.VK_ESCAPE});
        } finally {
            subscription.cancel();
        }
        Collections.sort(millis);
        return "raw-echo n=" + millis.size() + " p50=" + millis.get(14) + "ms p95=" + millis.get(28) + "ms max="
                + millis.get(29) + "ms";
    }

    private static List<String> latencyLines(List<String> lines) {
        return lines.stream().filter(line -> line.startsWith("input-latency")).toList();
    }

    // 사람이 치는 것처럼 글자를 하나씩. cmd 한 줄이 넘치지 않게 60자마다 Esc 로 지운다
    private static void type(TanagerTermWidget widget) {
        var panel = widget.getTerminalPanel();
        int count = (int) (System.nanoTime() / TimeUnit.MILLISECONDS.toNanos(TYPE_EVERY_MILLIS)) % 60;
        char key = count == 0 ? (char) KeyEvent.VK_ESCAPE : 'x';
        panel.processKeyEvent(
                new KeyEvent(panel, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, key));
    }

    private static TerminalStream launch(Pty4jLauncher launcher, List<String> argv) {
        LaunchCommand command =
                new LaunchCommand(argv, Optional.empty(), Map.of(), LaunchCommand.EnvNames.CASE_INSENSITIVE);
        return launcher.launch(command, SIZE).value().orElseThrow();
    }

    private static void setLookAndFeel(String className) {
        try {
            UIManager.setLookAndFeel(className);
        } catch (ReflectiveOperationException | javax.swing.UnsupportedLookAndFeelException e) {
            throw new IllegalStateException(e);
        }
    }
}
