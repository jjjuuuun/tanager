package tanager.adapter.pty;

import static org.assertj.core.api.Assertions.assertThat;

import com.pty4j.Command;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * pty4j 가 Windows 명령줄을 조립하는 규칙을 고정한다(#109).
 *
 * <p>#107 의 주입 방어(`WindowsLaunchCommands.requireSafeArgv`)와 명령줄 왕복 속성 테스트(platform/windows 의
 * `WslCommandLineModel`)는 pty4j 0.14.0 의 이 규칙을 전제로 한다. pty4j 를 올려 규칙이 바뀌면 여기서 먼저 실패한다 — 그때는 ADR-0014 의
 * #107 절과 모형을 다시 본다. 규칙: 공백 · 탭이 있고 {@code "} 로 시작하지 않는 원소만 {@code "…"} 로 감싼다, 원소 안의 {@code "} 는
 * 이스케이프하지 않는다, 감쌀 때 끝 {@code \} 는 하나만 겹친다.
 */
class Pty4jCommandLineQuotingTest {

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("공백 없는 원소는 그대로", List.of("wsl.exe", "-d", "Ubuntu"), "wsl.exe -d Ubuntu"),
                Arguments.of("공백이 있으면 감싼다", List.of("a", "b c"), "a \"b c\""),
                Arguments.of("탭도 감싼다", List.of("x\ty"), "\"x\ty\""),
                Arguments.of("한글 · 공백", List.of("--cd", "/home/me/한글 폴더"), "--cd \"/home/me/한글 폴더\""),
                Arguments.of("공백이 있고 \" 로 시작하면 그대로", List.of("\"a b"), "\"a b"),
                Arguments.of("감싼 원소 안의 \" 는 이스케이프하지 않는다", List.of("a \"b"), "\"a \"b\""),
                Arguments.of("공백 없는 원소 안의 \" 는 그대로", List.of("x\"y"), "x\"y"),
                Arguments.of("감쌀 때 끝 \\ 하나를 겹친다", List.of("a b\\"), "\"a b\\\\\""),
                Arguments.of("끝 \\ 가 둘이어도 하나만 더한다", List.of("a b\\\\"), "\"a b\\\\\\\""),
                Arguments.of("감싸지 않으면 끝 \\ 는 그대로", List.of("ab\\"), "ab\\"),
                Arguments.of("Tanager 의 rcfile 인자는 감싸지 않는다", List.of("\"$TANAGER_RC\""), "\"$TANAGER_RC\""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("pty4j 의 명령줄 조립이 #107 이 전제한 규칙과 같다")
    void commandLineMatchesAssumedRules(String caseName, List<String> argv, String expected) {
        // when
        String commandLine = new Command.CommandList(argv).toCommandLine();

        // then
        assertThat(commandLine)
                .as("pty4j 의 인용 규칙이 바뀌었다 — ADR-0014 #107 절과 WslCommandLineModel 을 다시 본다(%s)", caseName)
                .isEqualTo(expected);
    }
}
