package tanager.ui.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jediterm.core.Color;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.model.TerminalTextBuffer;
import java.awt.Font;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.out.ScriptedTerminalStream;
import tanager.application.port.out.TerminalConfig;
import tanager.ui.font.TerminalFontSet;
import tanager.ui.font.TerminalFonts;
import tanager.ui.theme.JediTermColors;
import tanager.ui.theme.SoftTheme;

/** 실행 중인 세션에 테마 · 글꼴 · 스크롤백을 바로 적용한다 — 셸은 다시 띄우지 않고 화면 버퍼도 그대로다(#185). */
class TanagerTermWidgetLiveTest {
    private static final Duration WAIT = Duration.ofSeconds(2);

    private final java.util.List<TanagerTermWidget> opened = new java.util.ArrayList<>();
    private final java.util.List<StreamTtyConnector> connectors = new java.util.ArrayList<>();

    @AfterEach
    void closeAll() throws Exception {
        SwingUtilities.invokeAndWait(() -> opened.forEach(TanagerTermWidget::close));
        connectors.forEach(StreamTtyConnector::close);
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

    private TanagerTermWidget widget(TerminalSettings settings) throws Exception {
        TanagerTermWidget widget = onEdt(() -> new TanagerTermWidget(settings));
        opened.add(widget);
        return widget;
    }

    // 화면에 놓인 것처럼 크기를 주고 셸을 붙인다 — 첫 크기가 셸에 한 번 간다
    private ScriptedTerminalStream start(TanagerTermWidget widget, ScriptedTerminalStream stream) throws Exception {
        StreamTtyConnector connector = StreamTtyConnector.attach(stream, "live");
        connectors.add(connector);
        onEdt(() -> {
            widget.setSize(800, 400);
            widget.getTerminalPanel().setSize(780, 400);
            widget.setTtyConnector(connector);
            widget.start();
            return null;
        });
        stream.start();
        return stream;
    }

    private static Color shownForeground(TerminalSettings settings, TextStyle style) {
        TerminalColor foreground = style.getForeground();
        if (foreground == null) {
            return settings.getDefaultForeground().toColor();
        }
        return foreground.isIndexed()
                ? settings.getTerminalColorPalette().getForeground(foreground)
                : foreground.toColor();
    }

    private static void writeLines(TanagerTermWidget widget, int count) {
        TerminalTextBuffer buffer = widget.getTerminalTextBuffer();
        buffer.modify(() -> {
            for (int i = 0; i < count; i++) {
                widget.getTerminal().writeCharacters("line " + i);
                widget.getTerminal().carriageReturn();
                widget.getTerminal().newLine();
            }
        });
    }

    @Test
    @DisplayName("테마를 바꾸면 실행 중 세션의 이미 그린 글자(ANSI 색 번호 · 리셋 뒤 기본색)와 배경이 새 테마 색이다 — 셸 · 버퍼는 그대로")
    void runningSessionFollowsTheme() throws Exception {
        // given: 빨강(SGR 31) 글자 하나, 리셋(SGR 0) 뒤 기본색 글자 하나
        TerminalSettings settings = new TerminalSettings(TerminalFontSet.fallback(), JediTermColors.of(SoftTheme.DARK));
        TanagerTermWidget widget = widget(settings);
        ScriptedTerminalStream stream =
                start(widget, new ScriptedTerminalStream(42).whenStarted(s -> s.emit("\u001b[31mR\u001b[0mD")));
        TerminalTextBuffer buffer = widget.getTerminalTextBuffer();
        await().atMost(WAIT).until(() -> buffer.getLine(0).getText().startsWith("RD"));
        JediTermColors light = JediTermColors.of(SoftTheme.LIGHT);

        // when
        onEdt(() -> {
            settings.colors(light);
            return null;
        });

        // then
        TextStyle red = buffer.getStyleAt(0, 0);
        TextStyle afterReset = buffer.getStyleAt(1, 0);
        assertThat(shownForeground(settings, red)).isEqualTo(light.palette().getForeground(TerminalColor.index(1)));
        assertThat(shownForeground(settings, afterReset))
                .isEqualTo(light.foreground().toColor());
        assertThat(widget.getTerminalPanel().getBackground().getRGB())
                .isEqualTo(light.background().toColor().getRGB());
        assertThat(buffer.getLine(0).getText()).startsWith("RD");
        assertThat(stream.closeCalls()).isZero();
        assertThat(stream.pid()).isEqualTo(42);
    }

    @Test
    @DisplayName("글꼴 크기를 16 으로 바꾸면 창마다 셸에 resize 가 한 번 간다 — 열 · 행은 줄어든다")
    void fontSizeResizesEachShellOnce() throws Exception {
        // given: 같은 설정을 쓰는 창 둘
        TerminalSettings settings = new TerminalSettings(TerminalFontSet.fallback(), JediTermColors.of(SoftTheme.DARK));
        ScriptedTerminalStream first = start(widget(settings), new ScriptedTerminalStream(1));
        ScriptedTerminalStream second = start(widget(settings), new ScriptedTerminalStream(2));
        await().atMost(WAIT)
                .until(() -> first.resizes().size() == 1 && second.resizes().size() == 1);
        await().during(Duration.ofMillis(200))
                .atMost(WAIT)
                .until(() -> first.resizes().size() == 1);

        // when
        onEdt(() -> settings.apply(new TerminalConfig("D2Coding", 16, 1.2, 10_000)));

        // then
        for (ScriptedTerminalStream stream : java.util.List.of(first, second)) {
            await().during(Duration.ofMillis(300))
                    .atMost(WAIT)
                    .until(() -> stream.resizes().size() == 2);
            assertThat(stream.resizes().get(1).columns())
                    .isLessThan(stream.resizes().get(0).columns());
        }
    }

    @Test
    @DisplayName("굵은 글자도 바뀐 크기의 번들 Bold 로 그린다")
    void boldFollowsFontSize() throws Exception {
        // given
        TerminalFontSet fonts = TerminalFonts.registerBundled().value().orElseThrow();
        TerminalSettings settings = new TerminalSettings(fonts, JediTermColors.of(SoftTheme.DARK));
        TanagerTermWidget widget = widget(settings);
        TextStyle bold = new TextStyle(null, null, EnumSet.of(TextStyle.Option.BOLD));

        // when
        Font shown = onEdt(() -> {
            settings.apply(new TerminalConfig("D2Coding", 16, 1.2, 10_000));
            TanagerTermWidget.BundledBoldPanel panel = (TanagerTermWidget.BundledBoldPanel) widget.getTerminalPanel();
            return panel.getFontToDisplay("a".toCharArray(), 0, 1, bold);
        });

        // then
        assertThat(shown.getFontName(Locale.ROOT)).isEqualTo("D2Coding Bold");
        assertThat(shown.getSize2D()).isEqualTo(16f);
    }

    @Test
    @DisplayName("스크롤백을 10000 → 100 으로 줄이면 오래된 줄을 버리고, 그 뒤에도 100 줄까지만 남는다")
    void scrollbackShrinks() throws Exception {
        // given
        TerminalSettings settings = new TerminalSettings(TerminalFontSet.fallback(), JediTermColors.of(SoftTheme.DARK));
        TanagerTermWidget widget = widget(settings);
        onEdt(() -> {
            settings.apply(new TerminalConfig("D2Coding", 14, 1.2, 10_000));
            return null;
        });
        writeLines(widget, 300);
        TerminalTextBuffer buffer = widget.getTerminalTextBuffer();
        assertThat(buffer.getHistoryLinesCount()).isGreaterThan(100);

        // when
        onEdt(() -> settings.apply(new TerminalConfig("D2Coding", 14, 1.2, 100)));

        // then
        assertThat(buffer.getHistoryLinesCount()).isEqualTo(100);
        writeLines(widget, 300);
        assertThat(buffer.getHistoryLinesCount()).isEqualTo(100);
        assertThat(buffer.getLine(-1).getText()).startsWith("line 2");
    }

    @Test
    @DisplayName("닫은 위젯은 설정 변경을 더 받지 않는다 — 닫힌 창이 남아 메모리를 잡지 않게")
    void closedWidgetDetaches() throws Exception {
        // given
        TerminalSettings settings = new TerminalSettings(TerminalFontSet.fallback(), JediTermColors.of(SoftTheme.DARK));
        TanagerTermWidget widget = widget(settings);
        int attached = settings.listenerCount();
        opened.remove(widget);

        // when
        onEdt(() -> {
            widget.close();
            return null;
        });

        // then
        assertThat(settings.listenerCount()).isEqualTo(attached - 1);
    }
}
