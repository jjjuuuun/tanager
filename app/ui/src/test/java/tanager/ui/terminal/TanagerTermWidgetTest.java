package tanager.ui.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jediterm.terminal.TextStyle;
import java.awt.Font;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.out.ScriptedTerminalStream;
import tanager.ui.font.TerminalFontSet;
import tanager.ui.font.TerminalFonts;
import tanager.ui.theme.JediTermColors;
import tanager.ui.theme.SoftDarkLaf;

class TanagerTermWidgetTest {
    private LookAndFeel previous;

    // LaF 는 JVM 전역이다. 소프트 테마를 남겨 두면 다른 테스트가 실행 순서에 따라 영향을 받는다
    @BeforeEach
    void rememberLookAndFeel() throws Exception {
        SwingUtilities.invokeAndWait(() -> previous = UIManager.getLookAndFeel());
    }

    @AfterEach
    void restoreLookAndFeel() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                UIManager.setLookAndFeel(previous);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static TanagerTermWidget newWidget(TerminalFontSet fonts) throws Exception {
        AtomicReference<TanagerTermWidget> widget = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                UIManager.setLookAndFeel(new SoftDarkLaf());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            widget.set(new TanagerTermWidget(new TerminalSettings(fonts, JediTermColors.current())));
        });
        return widget.get();
    }

    @Test
    @DisplayName("셸이 DA1(ESC [ c)을 물으면 JediTerm 이 ESC [ ? 6 c 로 바로 답한다(ConPTY 3초 지연 방지)")
    void answersDeviceAttributesQuery() throws Exception {
        // given
        ScriptedTerminalStream stream = new ScriptedTerminalStream(1).whenStarted(s -> s.emit("\u001b[c"));
        TanagerTermWidget widget = newWidget(TerminalFontSet.fallback());
        StreamTtyConnector connector = StreamTtyConnector.attach(stream, "da1");

        // when
        SwingUtilities.invokeAndWait(() -> {
            widget.setTtyConnector(connector);
            widget.start();
        });
        stream.start();

        // then
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> {
            StringBuilder written = new StringBuilder();
            for (byte[] chunk : stream.written()) {
                written.append(new String(chunk, StandardCharsets.UTF_8));
            }
            assertThat(written.toString()).contains("\u001b[?6c");
        });
        SwingUtilities.invokeAndWait(widget::close);
        connector.close();
    }

    @Test
    @DisplayName("굵은 글자는 합성 굵게가 아니라 번들 D2Coding Bold(14pt)로 그리고 보통 글자는 Regular 로 그린다")
    void boldUsesBundledBoldFace() throws Exception {
        // given
        TerminalFontSet fonts = TerminalFonts.registerBundled().value().orElseThrow();
        TanagerTermWidget widget = newWidget(fonts);
        TextStyle bold = new TextStyle(null, null, EnumSet.of(TextStyle.Option.BOLD));
        AtomicReference<Font> boldFont = new AtomicReference<>();
        AtomicReference<Font> plainFont = new AtomicReference<>();

        // when
        SwingUtilities.invokeAndWait(() -> {
            TanagerTermWidget.BundledBoldPanel panel = (TanagerTermWidget.BundledBoldPanel) widget.getTerminalPanel();
            boldFont.set(panel.getFontToDisplay("a".toCharArray(), 0, 1, bold));
            plainFont.set(panel.getFontToDisplay("a".toCharArray(), 0, 1, TextStyle.EMPTY));
        });

        // then
        assertThat(boldFont.get().getFontName(Locale.ROOT)).isEqualTo("D2Coding Bold");
        assertThat(boldFont.get().getSize2D()).isEqualTo(14f);
        assertThat(plainFont.get().getFontName(Locale.ROOT)).isEqualTo("D2Coding");
        SwingUtilities.invokeAndWait(widget::close);
    }
}
