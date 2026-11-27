package tanager.ui.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.model.StyleState;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.swing.JPanel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.out.TerminalConfig;
import tanager.ui.font.FontChoice;
import tanager.ui.font.FontFallback;
import tanager.ui.font.TerminalFontSet;
import tanager.ui.theme.JediTermColors;
import tanager.ui.theme.SoftTheme;

/** 실행 중에 바꾸는 터미널 설정: 글꼴 · 크기 · 줄 높이 · 스크롤백(FR-CFG-02)과 테마 색(FR-UI-05, #185). */
class TerminalSettingsLiveTest {
    private static final TerminalFontSet BUNDLED = TerminalFontSet.fallback();

    private final TerminalSettings settings = new TerminalSettings(BUNDLED, JediTermColors.of(SoftTheme.DARK));
    private final List<TerminalSettings.Change> changes = new ArrayList<>();

    {
        settings.addListener(changes::add);
    }

    private static TerminalConfig config(int size, double lineHeight, int scrollback) {
        return new TerminalConfig("D2Coding", size, lineHeight, scrollback);
    }

    @Test
    @DisplayName("크기 · 줄 높이 · 스크롤백을 바꾸면 글꼴(보통 · 굵게)이 그 크기가 되고 위젯들에 글꼴 · 스크롤백 변경을 알린다")
    void appliesTerminalConfig() {
        // when
        Optional<FontFallback> fallback = settings.apply(config(16, 1.5, 100));

        // then
        assertThat(fallback).isEmpty();
        assertThat(settings.getTerminalFont().getSize2D()).isEqualTo(16f);
        assertThat(settings.getTerminalFontSize()).isEqualTo(16f);
        assertThat(settings.fonts().bold().getSize2D()).isEqualTo(16f);
        assertThat(settings.getLineSpacing()).isEqualTo(1.5f);
        assertThat(settings.scrollback()).isEqualTo(100);
        assertThat(changes).containsExactly(TerminalSettings.Change.FONT, TerminalSettings.Change.SCROLLBACK);
    }

    @Test
    @DisplayName("JediTerm 버퍼의 상한은 설정 최댓값이다 — 실행 중에 스크롤백을 늘려도 기존 세션이 따라온다(줄 수는 위젯이 자른다)")
    void bufferCapacityIsMaximum() {
        // when
        settings.apply(config(14, 1.2, 100));

        // then
        assertThat(settings.getBufferMaxLinesCount()).isEqualTo(TerminalConfig.MAX_SCROLLBACK);
    }

    @Test
    @DisplayName("같은 터미널 설정을 다시 받으면 아무것도 알리지 않는다 — 다른 설정만 바뀐 SettingsChanged 에 셸 resize · 경고가 다시 나가지 않게")
    void sameConfigIsIgnored() {
        // given
        settings.apply(config(16, 1.2, 10_000));
        changes.clear();

        // when
        Optional<FontFallback> fallback = settings.apply(config(16, 1.2, 10_000));

        // then
        assertThat(fallback).isEmpty();
        assertThat(changes).isEmpty();
    }

    @Test
    @DisplayName("스크롤백만 바뀌면 글꼴 변경은 알리지 않는다(셸 resize 없음)")
    void scrollbackOnly() {
        // given
        settings.apply(config(14, 1.2, 10_000));
        changes.clear();

        // when
        settings.apply(config(14, 1.2, 100));

        // then
        assertThat(changes).containsExactly(TerminalSettings.Change.SCROLLBACK);
    }

    @Test
    @DisplayName("고른 글꼴을 쓸 수 없으면 경고를 돌려주고 번들 글꼴을 그 크기로 쓴다")
    void fallbackKeepsBundledFont() {
        // given
        FontFallback warning =
                new FontFallback("Consolas", BUNDLED.regular().getFamily(), FontFallback.Reason.NO_HANGUL);

        // when
        Optional<FontFallback> fallback = settings.apply(
                new TerminalConfig("Consolas", 18, 1.2, 10_000), name -> new FontChoice(BUNDLED, Optional.of(warning)));

        // then
        assertThat(fallback).contains(warning);
        assertThat(settings.getTerminalFont().getFamily())
                .isEqualTo(BUNDLED.regular().getFamily());
        assertThat(settings.getTerminalFont().getSize2D()).isEqualTo(18f);
    }

    @Test
    @DisplayName("테마 색을 바꾸면 팔레트 · 기본 전경 · 배경 · 선택색이 새 테마 값이고 위젯들에 색 변경을 알린다")
    void switchesColors() {
        // given
        JediTermColors light = JediTermColors.of(SoftTheme.LIGHT);

        // when
        settings.colors(light);

        // then
        assertThat(settings.getTerminalColorPalette()).isSameAs(light.palette());
        assertThat(settings.getDefaultForeground()).isEqualTo(light.foreground());
        assertThat(settings.getDefaultBackground()).isEqualTo(light.background());
        assertThat(settings.getSelectionColor()).isEqualTo(light.selection());
        assertThat(settings.getFoundPatternColor()).isEqualTo(light.foundPattern());
        assertThat(changes).containsExactly(TerminalSettings.Change.COLORS);
    }

    @Test
    @DisplayName("테마를 바꾸기 전에 만든 위젯의 스타일 상태도 새 테마의 기본 전경 · 배경을 쓴다")
    void styleStateFollowsColors() {
        // given
        StyleState state = settings.styleState();
        JediTermColors light = JediTermColors.of(SoftTheme.LIGHT);

        // when
        settings.colors(light);
        state.reset();

        // then
        TerminalColor foreground = state.getCurrent().getForeground();
        assertThat(foreground.toColor()).isEqualTo(light.foreground().toColor());
        assertThat(state.getDefaultBackground().toColor())
                .isEqualTo(light.background().toColor());
    }

    @Test
    @DisplayName("떼어 낸 리스너에는 알리지 않는다")
    void removedListenerIsSilent() {
        // given
        List<TerminalSettings.Change> other = new ArrayList<>();
        TerminalSettings.Listener listener = other::add;
        settings.addListener(listener);
        settings.removeListener(listener);

        // when
        settings.colors(JediTermColors.of(SoftTheme.LIGHT));

        // then
        assertThat(other).isEmpty();
    }

    @Test
    @DisplayName("칸 픽셀은 지금 글꼴 · 줄 높이로 잰다(창 최소 크기, #150)")
    void cellAreaUsesCurrentConfig() {
        // given
        settings.apply(config(20, 1.5, 10_000));
        JPanel component = new JPanel();
        Font font = settings.getTerminalFont();
        FontMetrics metrics = component.getFontMetrics(font);

        // when
        Dimension area = settings.cellArea(component, 20, 5);

        // then
        assertThat(area)
                .isEqualTo(new Dimension(20 * metrics.charWidth('W'), 5 * (int) Math.ceil(metrics.getHeight() * 1.5)));
    }
}
