package tanager.ui;

import static java.awt.event.InputEvent.CTRL_DOWN_MASK;
import static java.awt.event.InputEvent.SHIFT_DOWN_MASK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jediterm.terminal.ui.TerminalPanel;
import java.awt.event.KeyEvent;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.out.ScriptedTerminalStream;
import tanager.domain.layout.Orientation;
import tanager.ui.font.TerminalFontSet;
import tanager.ui.keys.KeyAction;
import tanager.ui.keys.KeyRoute;
import tanager.ui.keys.Keymap;
import tanager.ui.keys.LayoutAction;
import tanager.ui.terminal.StreamTtyConnector;
import tanager.ui.terminal.TanagerTermWidget;
import tanager.ui.terminal.TerminalSettings;
import tanager.ui.theme.JediTermColors;
import tanager.ui.theme.SoftDarkLaf;

/**
 * 인수 조건(#193): 메인 창의 판정을 거친 키만 실제 터미널(JediTerm)에 넣고 셸 스트림에 쓰인 바이트를 본다. 쓰기는 JediTerm 실행기에서 차례로
 * 일어나므로, "아무것도 가지 않았다"는 뒤에 보낸 표지 글자만 쓰였는지로 확인한다.
 */
class ShortcutKeysTerminalTest {
    private static final Duration WAIT = Duration.ofSeconds(2);
    private static final byte[] MARKER = {'q'};

    private final ManualTime time = new ManualTime();
    private final List<KeyAction> ran = new ArrayList<>();
    private final ScriptedTerminalStream stream = new ScriptedTerminalStream(1);
    private TanagerTermWidget widget;
    private StreamTtyConnector connector;
    private ShortcutKeys keys;
    private LookAndFeel previous;

    @BeforeEach
    void setUp() throws Exception {
        connector = StreamTtyConnector.attach(stream, "keys");
        widget = onEdt(() -> {
            previous = UIManager.getLookAndFeel();
            UIManager.setLookAndFeel(new SoftDarkLaf());
            TanagerTermWidget created =
                    new TanagerTermWidget(new TerminalSettings(TerminalFontSet.fallback(), JediTermColors.current()));
            created.setSize(800, 400);
            created.getTerminalPanel().setSize(780, 400);
            created.setTtyConnector(connector);
            created.start();
            return created;
        });
        stream.start();
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

    private void use(Keymap keymap) {
        keys = new ShortcutKeys(keymap, time, time, shown -> {});
    }

    // 메인 창의 KeyEventDispatcher 와 같다: 넘긴 키만 터미널이 받는다
    private void type(int keyCode, int modifiers, char typed) throws Exception {
        onEdt(() -> {
            TerminalPanel panel = widget.getTerminalPanel();
            List<KeyEvent> events = List.of(
                    new KeyEvent(panel, KeyEvent.KEY_PRESSED, 0, modifiers, keyCode, typed),
                    new KeyEvent(panel, KeyEvent.KEY_TYPED, 0, modifiers, KeyEvent.VK_UNDEFINED, typed),
                    new KeyEvent(panel, KeyEvent.KEY_RELEASED, 0, modifiers, keyCode, typed));
            for (KeyEvent event : events) {
                switch (keys.route(event)) {
                    case KeyRoute.Pass pass -> panel.processKeyEvent(event);
                    case KeyRoute.Swallow swallow -> {}
                    case KeyRoute.Run(KeyAction action) -> ran.add(action);
                }
            }
            return null;
        });
    }

    private void ctrlB() throws Exception {
        type(KeyEvent.VK_B, CTRL_DOWN_MASK, '\u0002');
    }

    // 표지 글자를 보내고 그것까지 쓰일 때까지 기다린다 → 그때까지 셸에 간 바이트 전부
    private byte[] shellReceivedThenMarker() throws Exception {
        type(KeyEvent.VK_Q, 0, 'q');
        await().atMost(WAIT).until(() -> endsWithMarker(written()));
        byte[] all = written();
        byte[] before = new byte[all.length - MARKER.length];
        System.arraycopy(all, 0, before, 0, before.length);
        return before;
    }

    private static boolean endsWithMarker(byte[] bytes) {
        return bytes.length >= MARKER.length && bytes[bytes.length - 1] == MARKER[0];
    }

    private byte[] written() {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (byte[] chunk : stream.written()) {
            all.writeBytes(chunk);
        }
        return all.toByteArray();
    }

    @Test
    @DisplayName("prefix 없음: Ctrl+B 는 셸에 0x02 로 간다")
    void noPrefixSendsCtrlB() throws Exception {
        // given
        use(Keymap.defaults());

        // when
        ctrlB();

        // then
        assertThat(shellReceivedThenMarker()).containsExactly(0x02);
    }

    @Test
    @DisplayName("prefix \"ctrl B\": Ctrl+B 다음 % 는 오른쪽 분할이고 셸에는 아무것도 가지 않는다")
    void prefixPercentSplitsWithoutTyping() throws Exception {
        // given
        use(ShortcutKeysTest.withPrefix("ctrl B"));

        // when
        ctrlB();
        type(KeyEvent.VK_5, SHIFT_DOWN_MASK, '%');

        // then
        assertThat(ran).containsExactly(new LayoutAction.Split(Orientation.ROW));
        assertThat(shellReceivedThenMarker()).isEmpty();
    }

    @Test
    @DisplayName("prefix \"ctrl B\": Ctrl+B 뒤 2초면 취소되고 다음 키는 셸로 간다")
    void timeoutPassesNextKey() throws Exception {
        // given
        use(ShortcutKeysTest.withPrefix("ctrl B"));
        ctrlB();

        // when
        time.advance(Duration.ofSeconds(2));
        type(KeyEvent.VK_5, SHIFT_DOWN_MASK, '%');

        // then
        assertThat(ran).isEmpty();
        assertThat(shellReceivedThenMarker()).containsExactly('%');
    }

    @Test
    @DisplayName("prefix \"ctrl B\": Ctrl+B 두 번이면 셸에 0x02 가 한 번 간다")
    void prefixTwiceSendsOneCtrlB() throws Exception {
        // given
        use(ShortcutKeysTest.withPrefix("ctrl B"));

        // when
        ctrlB();
        ctrlB();

        // then
        assertThat(shellReceivedThenMarker()).containsExactly(0x02);
    }

    @Test
    @DisplayName("prefix \"ctrl B\": Ctrl+B 다음 Esc 는 취소이고 Esc 는 셸로 가지 않는다")
    void escapeIsNotSent() throws Exception {
        // given
        use(ShortcutKeysTest.withPrefix("ctrl B"));

        // when
        ctrlB();
        type(KeyEvent.VK_ESCAPE, 0, '\u001b');

        // then
        assertThat(ran).isEmpty();
        assertThat(shellReceivedThenMarker()).isEmpty();
    }

    private static <T> T onEdt(Callable<T> task) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(task.call());
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
