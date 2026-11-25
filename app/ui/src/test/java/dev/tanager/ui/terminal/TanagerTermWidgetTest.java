package dev.tanager.ui.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.tanager.application.port.out.ScriptedTerminalStream;
import dev.tanager.ui.font.TerminalFontSet;
import dev.tanager.ui.theme.JediTermColors;
import dev.tanager.ui.theme.SoftDarkLaf;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.swing.LookAndFeel;
import javax.swing.UIManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TanagerTermWidgetTest {
    private LookAndFeel previous;

    // LaF 는 JVM 전역이다. 소프트 테마를 남겨 두면 "테마 없음" 을 전제로 한 다른 테스트(SoftLafDefaultsTest)가 깨진다
    @BeforeEach
    void rememberLookAndFeel() {
        previous = UIManager.getLookAndFeel();
    }

    @AfterEach
    void restoreLookAndFeel() throws Exception {
        UIManager.setLookAndFeel(previous);
    }

    @Test
    @DisplayName("셸이 DA1(ESC [ c)을 물으면 JediTerm 이 ESC [ ? 6 c 로 바로 답한다(ConPTY 3초 지연 방지)")
    void answersDeviceAttributesQuery() throws Exception {
        // given
        UIManager.setLookAndFeel(new SoftDarkLaf());
        ScriptedTerminalStream stream = new ScriptedTerminalStream(1).whenStarted(s -> s.emit("\u001b[c"));
        TanagerTermWidget widget =
                new TanagerTermWidget(new TerminalSettings(TerminalFontSet.fallback(), JediTermColors.current()));
        StreamTtyConnector connector = StreamTtyConnector.attach(stream, "da1");

        // when
        widget.setTtyConnector(connector);
        widget.start();
        stream.start();

        // then
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> {
            StringBuilder written = new StringBuilder();
            for (byte[] chunk : stream.written()) {
                written.append(new String(chunk, StandardCharsets.UTF_8));
            }
            assertThat(written.toString()).contains("\u001b[?6c");
        });
        widget.close();
        connector.close();
    }
}
