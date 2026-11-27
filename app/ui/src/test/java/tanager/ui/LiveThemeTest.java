package tanager.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.in.ResolvedAppearance;
import tanager.application.port.out.Theme;
import tanager.ui.font.TerminalFontSet;
import tanager.ui.terminal.TerminalSettings;
import tanager.ui.theme.ColorToken;
import tanager.ui.theme.JediTermColors;
import tanager.ui.theme.SoftDarkLaf;
import tanager.ui.theme.SoftLightLaf;
import tanager.ui.theme.SoftTheme;
import tanager.ui.theme.ThemeColors;
import tanager.ui.theme.Themed;

/** 정한 테마가 바뀌면 화면 전체 · 모든 터미널을 다시 만들지 않고 새 테마로 바꾼다(#185, FR-UI-01 · FR-UI-05). */
class LiveThemeTest {
    private final List<String> steps = new ArrayList<>();
    private final LiveTheme.Fade fade = new LiveTheme.Fade() {
        @Override
        public void cover() {
            steps.add("cover:" + UIManager.getLookAndFeel().getName());
        }

        @Override
        public void reveal() {
            steps.add("reveal:" + UIManager.getLookAndFeel().getName());
        }
    };
    private LookAndFeel previous;
    private TerminalSettings terminal;
    private JPanel root;
    private JLabel status;
    private LiveTheme theme;

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

    @BeforeEach
    void setUp() throws Exception {
        onEdt(() -> {
            previous = UIManager.getLookAndFeel();
            UIManager.setLookAndFeel(new SoftDarkLaf());
            terminal = new TerminalSettings(TerminalFontSet.fallback(), JediTermColors.current());
            root = Themed.background(new JPanel(), ColorToken.HEADER);
            status = Themed.foreground(new JLabel("~/work"), ColorToken.MUTED);
            root.add(status);
            theme = new LiveTheme(
                    SoftTheme.DARK,
                    terminal,
                    fade,
                    () -> List.<Component>of(root),
                    () -> steps.add("after:" + UIManager.getLookAndFeel().getName()));
            return null;
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        onEdt(() -> {
            UIManager.setLookAndFeel(previous);
            return null;
        });
    }

    @Test
    @DisplayName("다크 → 라이트: 스냅샷으로 덮고 → LaF 를 바꾸고 → 다시 칠한 뒤 → 페이드로 걷는다. 화면 · 터미널 색이 라이트 토큰이다")
    void switchesDarkToLightWithFade() throws Exception {
        // when
        boolean switched = onEdt(() -> theme.apply(new ResolvedAppearance(Theme.LIGHT, true)));

        // then
        assertThat(switched).isTrue();
        assertThat(steps)
                .containsExactly("cover:Tanager Soft Dark", "after:Tanager Soft Light", "reveal:Tanager Soft Light");
        assertThat(onEdt(() -> UIManager.getLookAndFeel())).isInstanceOf(SoftLightLaf.class);
        assertThat(onEdt(theme::current)).isEqualTo(SoftTheme.LIGHT);
        assertThat(onEdt(() -> root.getBackground())).isEqualTo(onEdt(() -> ThemeColors.get(ColorToken.HEADER)));
        assertThat(onEdt(() -> status.getForeground())).isEqualTo(onEdt(() -> ThemeColors.get(ColorToken.MUTED)));
        assertThat(root.getComponent(0)).isSameAs(status);
        JediTermColors light = JediTermColors.of(SoftTheme.LIGHT);
        assertThat(terminal.getDefaultBackground()).isEqualTo(light.background());
        assertThat(terminal.getDefaultForeground()).isEqualTo(light.foreground());
    }

    @Test
    @DisplayName("OS 애니메이션을 끄면 페이드 없이 바로 바뀐다")
    void switchesWithoutFadeWhenAnimationsOff() throws Exception {
        // when
        onEdt(() -> theme.apply(new ResolvedAppearance(Theme.LIGHT, false)));

        // then
        assertThat(steps).containsExactly("after:Tanager Soft Light");
        assertThat(onEdt(() -> UIManager.getLookAndFeel())).isInstanceOf(SoftLightLaf.class);
    }

    @Test
    @DisplayName("테마가 그대로면(움직임만 바뀌어도) 아무것도 다시 깔지 않는다 — 설정 LIGHT 에서 OS 가 다크로 바뀐 경우 등")
    void sameThemeDoesNothing() throws Exception {
        // given
        LookAndFeel installed = onEdt(UIManager::getLookAndFeel);

        // when
        boolean switched = onEdt(() -> theme.apply(new ResolvedAppearance(Theme.DARK, false)));

        // then
        assertThat(switched).isFalse();
        assertThat(steps).isEmpty();
        assertThat(onEdt(UIManager::getLookAndFeel)).isSameAs(installed);
    }

    @Test
    @DisplayName("라이트 → 다크로 다시 바꾸면 다크 토큰이다")
    void switchesBack() throws Exception {
        // given
        Color dark = onEdt(() -> ThemeColors.get(ColorToken.MUTED));
        onEdt(() -> theme.apply(new ResolvedAppearance(Theme.LIGHT, false)));

        // when
        onEdt(() -> theme.apply(new ResolvedAppearance(Theme.DARK, false)));

        // then
        assertThat(onEdt(() -> status.getForeground())).isEqualTo(dark);
        assertThat(terminal.getDefaultBackground())
                .isEqualTo(JediTermColors.of(SoftTheme.DARK).background());
    }

    @Test
    @DisplayName("트리 안의 메뉴도 새 LaF 의 색을 쓴다(updateComponentTreeUI)")
    void menusInTreeFollowLaf() throws Exception {
        // given
        JPopupMenu menu = onEdt(() -> {
            JPopupMenu created = new JPopupMenu();
            root.setComponentPopupMenu(created);
            return created;
        });

        // when
        onEdt(() -> theme.apply(new ResolvedAppearance(Theme.LIGHT, false)));

        // then
        assertThat(onEdt(menu::getBackground)).isEqualTo(onEdt(() -> UIManager.getColor("PopupMenu.background")));
    }
}
