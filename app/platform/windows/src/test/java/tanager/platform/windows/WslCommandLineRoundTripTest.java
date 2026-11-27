package tanager.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.out.Integration;
import tanager.domain.history.PathError;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.session.IntegrationMode;
import tanager.domain.session.LaunchTemplate;
import tanager.domain.session.ProfileId;
import tanager.domain.session.ShellKind;
import tanager.domain.session.ShellProfile;
import tanager.domain.session.WorkingDir;
import tanager.domain.shared.Result;
import tanager.platform.windows.WslCommandLineModel.WslInvocation;

/**
 * 명령줄 수준의 주입 방지(#107 인수 조건 2). 기동 argv 를 pty4j 처럼 이어 붙이고 wsl.exe 처럼 다시 읽으면 원래 argv 로 돌아와야 한다 —
 * 모형은 {@link WslCommandLineModel}. 순수 계산이라 OS 와 무관하게 돈다.
 */
class WslCommandLineRoundTripTest {
    private static final String WSL_EXE = "C:\\Windows\\System32\\wsl.exe";
    private static final ShellEnvironment UBUNTU = new ShellEnvironment.Wsl("Ubuntu");
    private static final ShellProfile WSL = new ShellProfile(
            new ProfileId("wsl:Ubuntu"),
            "Ubuntu",
            ShellKind.BASH,
            UBUNTU,
            new LaunchTemplate(WSL_EXE, List.of("-d", "Ubuntu")),
            IntegrationMode.OSC7);
    private static final Integration INSTALLED = new Integration.Installed(Path.of("사용자 'x 공백", "shell-integration"));
    private static final Integration UNAVAILABLE = new Integration.Unavailable(new Integration.Problem.NotSupported());
    private static final String INJECTION = "/tmp/q\" -- touch /tmp/proof #";

    private static List<String> launchArgv(WorkspacePath path, Integration integration) {
        return WindowsLaunchCommands.of(WSL, new WorkingDir.At(path), "C:\\Users\\me", Optional.empty(), integration)
                .argv();
    }

    private static List<String> bypassedArgv(String cd) {
        return List.of(WSL_EXE, "-d", "Ubuntu", "--cd", cd, "--", "bash", "-i");
    }

    private static boolean passesDefense(List<String> argv) {
        try {
            WindowsLaunchCommands.requireSafeArgv(UBUNTU, argv);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ---- 모형이 실측 규칙대로 주입을 재현하는지(모형이 무르면 아래 속성이 아무것도 증명하지 못한다) ----

    @Test
    @DisplayName("모형: 공백 원소를 감싼 --cd 값과 -- 뒤 명령을 그대로 읽는다")
    void modelReadsWrappedCdAndCommand() {
        // given
        String line = WslCommandLineModel.joinCmdArgs(bypassedArgv("/tmp/a b"));

        // when
        WslInvocation parsed = WslCommandLineModel.parseWsl(line);

        // then
        assertThat(line).isEqualTo(WSL_EXE + " -d Ubuntu --cd \"/tmp/a b\" -- bash -i");
        assertThat(parsed.arguments()).containsExactly(WSL_EXE, "-d", "Ubuntu", "--cd", "/tmp/a b");
        assertThat(parsed.command()).contains("bash -i");
    }

    @Test
    @DisplayName("모형: 방어 검사를 우회한 \" 경로는 따옴표를 닫고 뒤 문자열이 명령이 된다(#107 재현)")
    void modelReproducesInjection() {
        // given
        String line = WslCommandLineModel.joinCmdArgs(bypassedArgv(INJECTION));

        // when
        WslInvocation parsed = WslCommandLineModel.parseWsl(line);

        // then
        assertThat(parsed.arguments()).containsExactly(WSL_EXE, "-d", "Ubuntu", "--cd", "/tmp/q");
        assertThat(parsed.command())
                .hasValueSatisfying(command -> assertThat(command).startsWith("touch /tmp/proof"));
        assertThat(WslCommandLineModel.roundTrips(bypassedArgv(INJECTION))).isFalse();
    }

    @Test
    @DisplayName("모형: --cd 값 뒤에 남은 토큰은 -- 가 없어도 명령이 된다")
    void modelTreatsLeftoverTokenAsCommand() {
        // when
        WslInvocation parsed = WslCommandLineModel.parseWsl(WSL_EXE + " -d Ubuntu --cd /tmp/q touch /tmp/proof");

        // then
        assertThat(parsed.command()).contains("touch /tmp/proof");
    }

    @Test
    @DisplayName("모형: 공백이 없어 감싸지지 않는 원소도 \" 로 시작하면 다음 \" 까지 뒤 인자를 삼킨다(-- 앞 \" 를 모두 거부하는 이유)")
    void modelShowsUnwrappedLeadingQuoteSwallowsArguments() {
        // given
        List<String> argv = List.of(WSL_EXE, "-d", "Ubuntu", "--cd", "\"/tmp/q", "--", "x\"", "touch", "/tmp/proof");

        // when
        WslInvocation parsed = WslCommandLineModel.parseWsl(WslCommandLineModel.joinCmdArgs(argv));

        // then
        assertThat(parsed.arguments()).containsExactly(WSL_EXE, "-d", "Ubuntu", "--cd", "/tmp/q -- x");
        assertThat(parsed.command()).contains("touch /tmp/proof");
        assertThat(passesDefense(argv)).isFalse();
    }

    @Test
    @DisplayName("모형: 비따옴표 토큰 중간의 \" · \\ 와 유니코드 공백은 글자 그대로 한 토큰이다")
    void modelKeepsMidTokenQuoteBackslashAndUnicodeSpace() {
        // when
        WslInvocation parsed = WslCommandLineModel.parseWsl(WSL_EXE + " --cd /a\"b\\c\u3000d\u00a0e -- x");

        // then
        assertThat(parsed.arguments()).containsExactly(WSL_EXE, "--cd", "/a\"b\\c\u3000d\u00a0e");
        assertThat(parsed.command()).contains("x");
    }

    @Test
    @DisplayName("주입 경로는 WorkspacePath 가 UnsafeCharacter 로, 우회한 argv 는 방어 검사가 거부한다")
    void injectionIsRejectedAtBothLayers() {
        // given / when
        Result<WorkspacePath, PathError> path = WorkspacePath.of(UBUNTU, INJECTION);

        // then
        assertThat(path.error()).containsInstanceOf(PathError.UnsafeCharacter.class);
        assertThatThrownBy(() -> WindowsLaunchCommands.requireSafeArgv(UBUNTU, bypassedArgv(INJECTION)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- 속성 ----

    @Property
    @Label("WorkspacePath 가 허용한 WSL 경로로 만든 기동 argv 는 명령줄로 이었다 wsl.exe 처럼 읽어도 원래 argv 다")
    void acceptedPathRoundTrips(@ForAll("acceptedLooking") String tail) {
        // given
        Result<WorkspacePath, PathError> path = WorkspacePath.of(UBUNTU, "/" + tail);
        assertThat(path.isOk()).as("생성기에는 거부 문자가 없다: %s", tail).isTrue();

        // when / then
        for (Integration integration : List.of(INSTALLED, UNAVAILABLE)) {
            List<String> argv = launchArgv(path.value().orElseThrow(), integration);
            assertThat(WslCommandLineModel.roundTrips(argv))
                    .as("argv %s → %s", argv, WslCommandLineModel.joinCmdArgs(argv))
                    .isTrue();
        }
    }

    @Property
    @Label("거부 문자를 섞어도 WorkspacePath 가 허용한 경로는 항상 원래 argv 로 돌아온다")
    void anyAcceptedPathRoundTrips(@ForAll("anything") String tail) {
        Result<WorkspacePath, PathError> path = WorkspacePath.of(UBUNTU, "/" + tail);
        if (path.isOk()) {
            assertThat(WslCommandLineModel.roundTrips(launchArgv(path.value().orElseThrow(), INSTALLED)))
                    .isTrue();
        }
    }

    @Property
    @Label("WorkspacePath 를 우회한 --cd 값이라도 방어 검사를 통과한 argv 는 항상 원래 argv 로 돌아온다")
    void defenseAloneKeepsArgvIntact(@ForAll("anything") String tail) {
        List<String> argv = bypassedArgv("/" + tail);
        if (passesDefense(argv)) {
            assertThat(WslCommandLineModel.roundTrips(argv))
                    .as("argv %s → %s", argv, WslCommandLineModel.joinCmdArgs(argv))
                    .isTrue();
        }
    }

    /** 허용돼야 하는 글자: 공백 · 셸 특수 문자 · 한글 · C1 · 유니코드 공백 · 줄 구분자 · 따옴표 닮은 글자. */
    @Provide
    Arbitrary<String> acceptedLooking() {
        return Arbitraries.strings()
                .withChars("aZ0._-/ '$`#;&|()*?!~%{}[]<>=".toCharArray())
                .withChars('한', '글')
                .withChars('\u0080', '\u0085', '\u009b', '\u009f')
                .withChars('\u00a0', '\u2028', '\u2029', '\u3000', '\u200b')
                .withChars('\u201c', '\u201d', '\uff02', '\u02ba', '\u2033')
                .ofMaxLength(24);
    }

    /** 위 글자에 거부돼야 하는 글자({@code "} · {@code \} · 탭 · 줄바꿈 · NUL · ESC · DEL)를 섞는다. */
    @Provide
    Arbitrary<String> anything() {
        return Arbitraries.oneOf(
                acceptedLooking(),
                Arbitraries.strings()
                        .withChars("a /'$`\"\\\t\n\u0000\u001b\u007f".toCharArray())
                        .withChars('한', '\u0085', '\u2028', '\u3000')
                        .ofMaxLength(16));
    }
}
