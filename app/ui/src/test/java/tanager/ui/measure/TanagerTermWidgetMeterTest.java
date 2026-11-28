package tanager.ui.measure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jediterm.terminal.model.TerminalTextBuffer;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.out.ScriptedTerminalStream;
import tanager.domain.shared.PaneId;
import tanager.ui.font.TerminalFontSet;
import tanager.ui.terminal.StreamTtyConnector;
import tanager.ui.terminal.TanagerTermWidget;
import tanager.ui.terminal.TerminalSettings;
import tanager.ui.theme.JediTermColors;
import tanager.ui.theme.SoftDarkLaf;

/**
 * 터미널 위젯이 측정 고리에 키 · 출력 · 그림을 알린다(#222). 셸은 쓴 바이트를 그대로 돌려주는 가짜이고 시계는 손으로 진행한다 — 판정은 시간이
 * 아니라 짝짓기다.
 */
class TanagerTermWidgetMeterTest {
    private static final String READY = "ready";
    // 첫 위젯은 글꼴 · 클래스 적재로 늦게 뜬다. 준비 · 에코를 기다릴 뿐 판정이 아니다
    private static final Duration WAIT = Duration.ofSeconds(10);

    private final AtomicLong nanos = new AtomicLong();
    private final InputLatencyProbe probe =
            (InputLatencyProbe) Measurement.of(true, nanos::get).pane(new PaneId("p1"));
    private final ScriptedTerminalStream stream = new ScriptedTerminalStream(1).echoWrites();
    private StreamTtyConnector connector;
    private TanagerTermWidget widget;
    private LookAndFeel previous;

    @BeforeEach
    void setUp() throws Exception {
        connector = StreamTtyConnector.attach(stream, "meter");
        widget = onEdt(() -> {
            previous = UIManager.getLookAndFeel();
            UIManager.setLookAndFeel(new SoftDarkLaf());
            TanagerTermWidget created = new TanagerTermWidget(
                    new TerminalSettings(TerminalFontSet.fallback(), JediTermColors.current()), probe);
            created.setSize(800, 400);
            created.getTerminalPanel().setSize(780, 400);
            created.setTtyConnector(connector);
            created.start();
            return created;
        });
        stream.start();
        // 키 처리기는 에뮬레이터 스레드가 붙인다 — 출력이 화면 버퍼에 보이면 키가 셸로 간다
        stream.emit(READY);
        await().atMost(WAIT).until(() -> line().startsWith(READY));
    }

    @AfterEach
    void tearDown() throws Exception {
        onEdt(() -> {
            widget.close();
            UIManager.setLookAndFeel(previous);
            return null;
        });
        connector.close();
    }

    @Test
    @DisplayName("키를 보내고 에코가 버퍼에 들어간 뒤 그리면 그 사이가 표본 하나다")
    void keyEchoPaint_recordsOneSample() throws Exception {
        // given — 준비 출력 · 그 그림은 키의 짝이 아니다
        paint();
        nanos.set(0);
        onEdt(() -> {
            var panel = widget.getTerminalPanel();
            panel.processKeyEvent(new KeyEvent(panel, KeyEvent.KEY_TYPED, 0, 0, KeyEvent.VK_UNDEFINED, 'a'));
            return null;
        });
        await().atMost(WAIT).until(() -> line().startsWith(READY + "a"));
        nanos.set(TimeUnit.MILLISECONDS.toNanos(7));

        // when
        paint();

        // then
        assertThat(probe.report()).contains("input-latency pane=p1 n=1 p50=7ms p95=7ms p99=7ms max=7ms");
    }

    private void paint() throws Exception {
        onEdt(() -> {
            BufferedImage image = new BufferedImage(780, 400, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            try {
                widget.getTerminalPanel().paintComponent(g);
            } finally {
                g.dispose();
            }
            return null;
        });
    }

    private String line() {
        TerminalTextBuffer buffer = widget.getTerminalTextBuffer();
        return buffer.getLine(0).getText();
    }

    private static <T> T onEdt(Callable<T> work) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(work.call());
            } catch (Exception e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }
}
