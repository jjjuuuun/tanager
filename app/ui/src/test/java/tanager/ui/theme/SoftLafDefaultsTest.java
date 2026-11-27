package tanager.ui.theme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.awt.Color;
import javax.swing.UIDefaults;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** FlatLaf 는 잘못된 값이면 로그만 남기고 null 을 주므로, LaF 가 만든 기본값을 직접 확인한다(headless). */
class SoftLafDefaultsTest {
    private static UIDefaults defaults(SoftTheme theme) {
        return switch (theme) {
            case DARK -> new SoftDarkLaf().getDefaults();
            case LIGHT -> new SoftLightLaf().getDefaults();
        };
    }

    private static Rgb rgb(Color c) {
        return new Rgb(c.getRed(), c.getGreen(), c.getBlue());
    }

    @ParameterizedTest
    @EnumSource(SoftTheme.class)
    @DisplayName("토큰 키 12개가 null 이 아니고 테마 파일 값과 같다")
    void tokenKeysMatchFile(SoftTheme theme) {
        // given
        UIDefaults d = defaults(theme);
        ThemeTokens expected = theme.load().tokens();

        // then
        for (ColorToken t : ColorToken.values()) {
            Color c = d.getColor(t.key());
            assertThat(c).as(t.key()).isNotNull();
            assertThat(rgb(c)).as(t.key()).isEqualTo(expected.get(t));
        }
    }

    @ParameterizedTest
    @EnumSource(SoftTheme.class)
    @DisplayName("터미널 색 키(전경·배경·커서·선택·ansi 16)가 null 이 아니고 팔레트와 같다")
    void terminalKeysMatchPalette(SoftTheme theme) {
        UIDefaults d = defaults(theme);
        TerminalPalette p = theme.load().terminal();

        assertThat(rgb(d.getColor("Tanager.term.foreground"))).isEqualTo(p.foreground());
        assertThat(rgb(d.getColor("Tanager.term.background"))).isEqualTo(p.background());
        assertThat(rgb(d.getColor("Tanager.term.cursor"))).isEqualTo(p.cursor());
        assertThat(rgb(d.getColor("Tanager.term.selection"))).isEqualTo(p.selection());
        for (int i = 0; i < 16; i++) {
            Color c = d.getColor("Tanager.term.ansi." + i);
            assertThat(c).as("ansi " + i).isNotNull();
            assertThat(rgb(c)).as("ansi " + i).isEqualTo(p.ansi(i));
        }
    }

    @ParameterizedTest
    @EnumSource(SoftTheme.class)
    @DisplayName("모서리 반지름 · 기반 색 · 포커스 색이 설계대로다")
    void shapeAndBaseColors(SoftTheme theme) {
        UIDefaults d = defaults(theme);
        ThemeTokens t = theme.load().tokens();

        assertThat(d.getInt("Component.arc")).isEqualTo(8);
        assertThat(d.getInt("Button.arc")).isEqualTo(8);
        assertThat(d.getInt("Tanager.pane.arc")).isEqualTo(6);
        assertThat(rgb(d.getColor("Panel.background"))).isEqualTo(t.get(ColorToken.WINDOW));
        assertThat(rgb(d.getColor("Component.focusColor"))).isEqualTo(t.get(ColorToken.ACCENT));
        // 시안 D: 제목 표시줄은 본문(window)보다 한 단 어두운 header 색이다 — FlatLaf 기본 unifiedBackground(true)를 끈다
        assertThat(d.getBoolean("TitlePane.unifiedBackground")).isFalse();
        assertThat(rgb(d.getColor("TitlePane.background"))).isEqualTo(t.get(ColorToken.HEADER));
    }

    @Test
    @DisplayName("ThemeColors 는 UIManager 의 토큰 색을 돌려준다")
    void themeColorsReadsUiManager() {
        // given
        javax.swing.UIManager.put(ColorToken.ACCENT.key(), new Color(1, 2, 3));
        try {
            // when / then
            assertThat(ThemeColors.get(ColorToken.ACCENT)).isEqualTo(new Color(1, 2, 3));
        } finally {
            javax.swing.UIManager.put(ColorToken.ACCENT.key(), null);
        }
    }

    @Test
    @DisplayName("테마가 설치되지 않아 값이 없으면 ThemeColors 는 IllegalStateException")
    void themeColorsWithoutThemeThrows() throws Exception {
        // given: 소프트 테마가 아닌 LaF 를 명시적으로 깐다(LaF 는 JVM 전역 — 다른 테스트가 남긴 테마에 기대지 않는다)
        javax.swing.LookAndFeel previous = javax.swing.UIManager.getLookAndFeel();
        javax.swing.UIManager.setLookAndFeel(new javax.swing.plaf.metal.MetalLookAndFeel());
        javax.swing.UIManager.put(ColorToken.ACCENT.key(), null);
        try {
            // when / then
            assertThatIllegalStateException().isThrownBy(() -> ThemeColors.get(ColorToken.ACCENT));
        } finally {
            javax.swing.UIManager.setLookAndFeel(previous);
        }
    }

    @ParameterizedTest
    @EnumSource(SoftTheme.class)
    @DisplayName("포커스를 잃은 선택 배경은 순수 검정이 아니고 글자 대비가 4.5 : 1 이상이며 사이드바와 구별된다(#63)")
    void inactiveSelectionIsReadable(SoftTheme theme) {
        // given
        UIDefaults d = defaults(theme);
        Rgb sidebar = theme.load().tokens().get(ColorToken.SIDEBAR);
        Rgb select = theme.load().tokens().get(ColorToken.SELECT);

        // then
        for (String component : java.util.List.of("Tree", "List", "Table")) {
            Rgb background = rgb(d.getColor(component + ".selectionInactiveBackground"));
            Rgb foreground = rgb(d.getColor(component + ".selectionInactiveForeground"));
            assertThat(background).as(component).isNotEqualTo(new Rgb(0, 0, 0));
            assertThat(Contrast.ratio(foreground, background)).as(component).isGreaterThanOrEqualTo(4.5);
            assertThat(background).as(component).isNotEqualTo(sidebar);
            // FlatLaf 의 파생값(다크 #0B0D10)처럼 검정 쪽으로 빠지지 않고 활성 선택색 가까이에 있다
            assertThat(Contrast.ratio(background, select))
                    .as(component)
                    .isLessThan(Contrast.ratio(background, new Rgb(0, 0, 0)));
        }
    }

    @ParameterizedTest
    @EnumSource(SoftTheme.class)
    @DisplayName("전환 시간 토큰은 150~200ms 다(FR-UI-04)")
    void motionDurationInRange(SoftTheme theme) {
        // given
        UIDefaults d = defaults(theme);

        // then
        assertThat(d.get("Tanager.motion.duration")).isInstanceOf(Integer.class);
        assertThat(d.getInt("Tanager.motion.duration")).isBetween(150, 200);
    }

    @ParameterizedTest
    @EnumSource(SoftTheme.class)
    @DisplayName("스크롤바는 소프트 스크롤바 UI 를 쓴다(#63)")
    void scrollBarUsesSoftUi(SoftTheme theme) {
        assertThat(defaults(theme).getString("ScrollBarUI")).isEqualTo(SoftScrollBarUI.class.getName());
    }

    @Test
    @DisplayName("스크롤바 손잡이는 평소 얇고 마우스를 올리면 굵어진다(#63)")
    void thumbIsThinUntilHovered() throws Exception {
        // given
        javax.swing.LookAndFeel previous = javax.swing.UIManager.getLookAndFeel();
        javax.swing.UIManager.setLookAndFeel(new SoftDarkLaf());
        try {
            SoftScrollBarUI ui = (SoftScrollBarUI) new javax.swing.JScrollBar().getUI();

            // when
            java.awt.Insets idle = ui.thumbInsets(false);
            java.awt.Insets hovered = ui.thumbInsets(true);

            // then
            assertThat(idle.left).isGreaterThan(hovered.left);
            assertThat(idle.right).isGreaterThan(hovered.right);
        } finally {
            javax.swing.UIManager.setLookAndFeel(previous);
        }
    }

    @ParameterizedTest
    @EnumSource(SoftTheme.class)
    @DisplayName("UI 글꼴은 논리 글꼴 Dialog 다 — 한국어 Windows 의 맑은 고딕은 \\ 를 ₩ 로 그린다(#125)")
    void uiFontIsLogicalDialog(SoftTheme theme) {
        // given
        UIDefaults d = defaults(theme);

        // then: 라틴 글자는 라틴 글꼴, 한글은 대체 글꼴로 그리는 합성 글꼴이라 \ 가 그대로 보인다
        for (String key : java.util.List.of("defaultFont", "Label.font", "Tree.font")) {
            assertThat(d.getFont(key)).as(key).isNotNull();
            assertThat(d.getFont(key).getFamily(java.util.Locale.ROOT)).as(key).isEqualTo(java.awt.Font.DIALOG);
        }
    }
}
