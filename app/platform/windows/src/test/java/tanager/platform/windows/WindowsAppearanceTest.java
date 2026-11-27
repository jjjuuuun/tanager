package tanager.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import tanager.application.port.out.SystemAppearance;
import tanager.application.port.out.Theme;

/** OS 다크/라이트(#184, FR-UI-01)와 '애니메이션 효과'(#152, FR-UI-04) 설정 읽기. */
class WindowsAppearanceTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final String ANIMATION_KEY = "HKCU\\Control Panel\\Desktop\\WindowMetrics";
    private static final String THEME_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";
    private static final List<String> THEME_QUERY = List.of("reg.exe", "query", THEME_KEY, "/v", "AppsUseLightTheme");
    private static final List<String> ANIMATION_QUERY = List.of("reg.exe", "query", ANIMATION_KEY, "/v", "MinAnimate");

    private static byte[] minAnimateOutput(String value) {
        String text = "\r\nHKEY_CURRENT_USER\\Control Panel\\Desktop\\WindowMetrics\r\n"
                + "    MinAnimate    REG_SZ    " + value + "\r\n\r\n";
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    // Windows 11 reg.exe 의 실제 출력 모양(2026-11-28, 이 PC 에서 받은 바이트 그대로)
    private static byte[] lightThemeOutput(String value) {
        String text = "\r\nHKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize\r\n"
                + "    AppsUseLightTheme    REG_DWORD    " + value + "\r\n\r\n";
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static ScriptedCommandRunner registry(CommandOutcome theme, CommandOutcome animation) {
        return new ScriptedCommandRunner().on(THEME_QUERY, theme).on(ANIMATION_QUERY, animation);
    }

    @Test
    @DisplayName("MinAnimate 가 0 이면 애니메이션 꺼짐이다")
    void zeroMeansOff() {
        // when / then
        assertThat(WindowsAppearance.minAnimate(minAnimateOutput("0"))).contains(false);
    }

    @Test
    @DisplayName("MinAnimate 가 1 이면 애니메이션 켜짐이다")
    void oneMeansOn() {
        // when / then
        assertThat(WindowsAppearance.minAnimate(minAnimateOutput("1"))).contains(true);
    }

    @Test
    @DisplayName("값 줄이 없거나 출력이 비면 알 수 없음이다")
    void missingValueIsUnknown() {
        // when / then
        assertThat(WindowsAppearance.minAnimate(new byte[0])).isEmpty();
        assertThat(WindowsAppearance.minAnimate("HKEY_CURRENT_USER\\Control Panel\\Desktop\\WindowMetrics\r\n"
                        .getBytes(StandardCharsets.US_ASCII)))
                .isEmpty();
        assertThat(WindowsAppearance.minAnimate("    MinAnimate    REG_SZ\r\n".getBytes(StandardCharsets.US_ASCII)))
                .isEmpty();
    }

    @Test
    @DisplayName("값 이름의 대소문자와 줄바꿈(LF)은 상관없다")
    void caseAndLineEndingsDoNotMatter() {
        // given
        byte[] output = "\nHKEY_CURRENT_USER\\Control Panel\\Desktop\\WindowMetrics\n    minanimate    REG_SZ    0\n"
                .getBytes(StandardCharsets.US_ASCII);

        // when / then
        assertThat(WindowsAppearance.minAnimate(output)).contains(false);
    }

    @Test
    @DisplayName("AppsUseLightTheme 가 0x0 이면 다크, 0x1 이면 라이트다")
    void lightThemeFlagMapsToTheme() {
        // when / then
        assertThat(WindowsAppearance.appsUseLightTheme(lightThemeOutput("0x0"))).contains(Theme.DARK);
        assertThat(WindowsAppearance.appsUseLightTheme(lightThemeOutput("0x1"))).contains(Theme.LIGHT);
    }

    @Test
    @DisplayName("AppsUseLightTheme 값 줄이 없거나 값이 0x0 · 0x1 이 아니면 알 수 없음이다")
    void lightThemeUnknownValues() {
        // when / then
        assertThat(WindowsAppearance.appsUseLightTheme(new byte[0])).isEmpty();
        assertThat(WindowsAppearance.appsUseLightTheme(
                        "HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize\r\n"
                                .getBytes(StandardCharsets.US_ASCII)))
                .isEmpty();
        assertThat(WindowsAppearance.appsUseLightTheme(lightThemeOutput("0x2"))).isEmpty();
        assertThat(WindowsAppearance.appsUseLightTheme(lightThemeOutput("dark")))
                .isEmpty();
    }

    @Test
    @DisplayName("reg.exe 로 HKCU 의 AppsUseLightTheme 와 MinAnimate 를 각각 묻는다")
    void queriesBothRegistryValues() {
        // given
        ScriptedCommandRunner runner = registry(
                new CommandOutcome.Exited(0, lightThemeOutput("0x1")),
                new CommandOutcome.Exited(0, minAnimateOutput("0")));

        // when
        SystemAppearance appearance = WindowsAppearance.read(runner, TIMEOUT);

        // then
        assertThat(appearance).isEqualTo(new SystemAppearance(Optional.of(Theme.LIGHT), false));
        assertThat(runner.commands()).containsExactly(THEME_QUERY, ANIMATION_QUERY);
        assertThat(runner.timeouts()).containsExactly(TIMEOUT, TIMEOUT);
    }

    @Test
    @DisplayName("테마 값이 없거나(종료 코드 1) 실행하지 못하거나 시간을 넘기면 테마는 empty 다 — 애니메이션은 따로 읽는다")
    void themeFailuresMeanEmpty() {
        // given
        List<CommandOutcome> failures = List.of(
                new CommandOutcome.Exited(1, new byte[0]),
                new CommandOutcome.Exited(0, new byte[0]),
                new CommandOutcome.Exited(2, lightThemeOutput("0x1")),
                new CommandOutcome.NotStarted("reg.exe 없음"),
                new CommandOutcome.TimedOut(TIMEOUT));

        // then
        for (CommandOutcome outcome : failures) {
            SystemAppearance appearance = WindowsAppearance.read(
                    registry(outcome, new CommandOutcome.Exited(0, minAnimateOutput("0"))), TIMEOUT);
            assertThat(appearance).as(outcome.toString()).isEqualTo(new SystemAppearance(Optional.empty(), false));
        }
    }

    @Test
    @DisplayName("애니메이션 값이 없거나(종료 코드 1) 실행하지 못하거나 시간을 넘기면 애니메이션을 켠다")
    void animationFailuresMeanEnabled() {
        // given
        List<CommandOutcome> failures = List.of(
                new CommandOutcome.Exited(1, new byte[0]),
                new CommandOutcome.Exited(0, new byte[0]),
                new CommandOutcome.NotStarted("reg.exe 없음"),
                new CommandOutcome.TimedOut(TIMEOUT));

        // then
        for (CommandOutcome outcome : failures) {
            SystemAppearance appearance = WindowsAppearance.read(
                    registry(new CommandOutcome.Exited(0, lightThemeOutput("0x0")), outcome), TIMEOUT);
            assertThat(appearance)
                    .as(outcome.toString())
                    .isEqualTo(new SystemAppearance(Optional.of(Theme.DARK), true));
        }
    }

    @Test
    @DisplayName("값이 0 이어도 종료 코드가 0 이 아니면 믿지 않는다")
    void nonZeroExitIgnoresOutput() {
        // given
        ScriptedCommandRunner runner =
                registry(new CommandOutcome.NotStarted("x"), new CommandOutcome.Exited(2, minAnimateOutput("0")));

        // when / then
        assertThat(WindowsAppearance.read(runner, TIMEOUT).animations()).isTrue();
    }

    @Test
    @Tag("windows")
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("실제 reg.exe 로 읽어도 예외 없이 값을 돌려준다")
    void readsRealRegistry() {
        // when — 이 PC 의 값은 모르지만 값 줄은 있어야 한다(없으면 읽는 명령이 틀린 것)
        CommandOutcome outcome = new ProcessCommandRunner().run(ANIMATION_QUERY, TIMEOUT);
        SystemAppearance appearance = WindowsAppearance.read(new ProcessCommandRunner(), TIMEOUT);

        // then
        assertThat(outcome).isInstanceOf(CommandOutcome.Exited.class);
        byte[] stdout = ((CommandOutcome.Exited) outcome).stdout();
        assertThat(WindowsAppearance.minAnimate(stdout)).isPresent();
        assertThat(appearance.animations())
                .isEqualTo(WindowsAppearance.minAnimate(stdout).orElseThrow());
    }

    @Test
    @Tag("windows")
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("실제 reg.exe 로 읽으면 테마가 empty 가 아니다(Windows 10 1809+ 는 값이 있다)")
    void readsRealThemeFromRegistry() {
        // when
        SystemAppearance appearance = WindowsAppearance.read(new ProcessCommandRunner(), TIMEOUT);

        // then
        assertThat(appearance.theme()).isPresent();
    }
}
