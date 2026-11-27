package tanager.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import tanager.application.port.out.SystemAppearance;

/** OS '애니메이션 효과' 설정 읽기(#152, FR-UI-04). */
class WindowsAppearanceTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final String KEY = "HKCU\\Control Panel\\Desktop\\WindowMetrics";

    private static byte[] regOutput(String value) {
        String text = "\r\nHKEY_CURRENT_USER\\Control Panel\\Desktop\\WindowMetrics\r\n"
                + "    MinAnimate    REG_SZ    " + value + "\r\n\r\n";
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    @DisplayName("MinAnimate 가 0 이면 애니메이션 꺼짐이다")
    void zeroMeansOff() {
        // when / then
        assertThat(WindowsAppearance.minAnimate(regOutput("0"))).contains(false);
    }

    @Test
    @DisplayName("MinAnimate 가 1 이면 애니메이션 켜짐이다")
    void oneMeansOn() {
        // when / then
        assertThat(WindowsAppearance.minAnimate(regOutput("1"))).contains(true);
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
    @DisplayName("reg.exe 로 HKCU 의 MinAnimate 값을 묻는다")
    void queriesRegistryValue() {
        // given
        FakeCommandRunner runner = new FakeCommandRunner(new CommandOutcome.Exited(0, regOutput("0")));

        // when
        SystemAppearance appearance = WindowsAppearance.read(runner, TIMEOUT);

        // then
        assertThat(appearance.animations()).isFalse();
        assertThat(runner.commands()).containsExactly(List.of("reg.exe", "query", KEY, "/v", "MinAnimate"));
        assertThat(runner.timeouts()).containsExactly(TIMEOUT);
    }

    @Test
    @DisplayName("값이 없거나(종료 코드 1) 실행하지 못하거나 시간을 넘기면 애니메이션을 켠다")
    void failuresMeanEnabled() {
        // given
        List<CommandOutcome> failures = List.of(
                new CommandOutcome.Exited(1, new byte[0]),
                new CommandOutcome.Exited(0, new byte[0]),
                new CommandOutcome.NotStarted("reg.exe 없음"),
                new CommandOutcome.TimedOut(TIMEOUT));

        // then
        for (CommandOutcome outcome : failures) {
            assertThat(WindowsAppearance.read(new FakeCommandRunner(outcome), TIMEOUT)
                            .animations())
                    .as(outcome.toString())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("값이 0 이어도 종료 코드가 0 이 아니면 믿지 않는다")
    void nonZeroExitIgnoresOutput() {
        // given
        FakeCommandRunner runner = new FakeCommandRunner(new CommandOutcome.Exited(2, regOutput("0")));

        // when / then
        assertThat(WindowsAppearance.read(runner, TIMEOUT).animations()).isTrue();
    }

    @Test
    @Tag("windows")
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("실제 reg.exe 로 읽어도 예외 없이 값을 돌려준다")
    void readsRealRegistry() {
        // when — 이 PC 의 값은 모르지만 값 줄은 있어야 한다(없으면 읽는 명령이 틀린 것)
        CommandOutcome outcome =
                new ProcessCommandRunner().run(List.of("reg.exe", "query", KEY, "/v", "MinAnimate"), TIMEOUT);
        SystemAppearance appearance = WindowsAppearance.read(new ProcessCommandRunner(), TIMEOUT);

        // then
        assertThat(outcome).isInstanceOf(CommandOutcome.Exited.class);
        byte[] stdout = ((CommandOutcome.Exited) outcome).stdout();
        assertThat(WindowsAppearance.minAnimate(stdout)).isPresent();
        assertThat(appearance.animations())
                .isEqualTo(WindowsAppearance.minAnimate(stdout).orElseThrow());
    }
}
