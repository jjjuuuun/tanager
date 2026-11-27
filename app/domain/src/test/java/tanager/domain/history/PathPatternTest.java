package tanager.domain.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PathPatternTest {
    private static final ShellEnvironment WIN = new ShellEnvironment.Windows();
    private static final ShellEnvironment UBUNTU = new ShellEnvironment.Wsl("Ubuntu");

    private static WorkspacePath path(ShellEnvironment env, String raw) {
        return WorkspacePath.of(env, raw).value().orElseThrow();
    }

    private static PathPattern pattern(String raw) {
        return PathPattern.of(raw).value().orElseThrow();
    }

    static Stream<Arguments> table() {
        return Stream.of(
                Arguments.of("/tmp/**", UBUNTU, "/tmp", true),
                Arguments.of("/tmp/**", UBUNTU, "/tmp/a/b", true),
                Arguments.of("/tmp/**", UBUNTU, "/tmpx", false),
                Arguments.of("/tmp/**", UBUNTU, "/var/tmp", false),
                Arguments.of("/tmp/**", WIN, "C:\\tmp", false),
                Arguments.of("**/node_modules/**", UBUNTU, "/a/node_modules/b", true),
                Arguments.of("**/node_modules/**", WIN, "C:\\x\\node_modules\\y", true),
                Arguments.of("**/node_modules/**", UBUNTU, "/node_modules", true),
                Arguments.of("**/node_modules/**", UBUNTU, "/a/node_modulesx/b", false),
                Arguments.of("**\\node_modules\\**", UBUNTU, "/a/node_modules", true),
                Arguments.of("C:\\Temp\\**", WIN, "c:\\temp\\a", true),
                Arguments.of("C:\\Temp\\**", WIN, "D:\\Temp\\a", false),
                Arguments.of("C:\\Temp\\**", UBUNTU, "/mnt/c/Temp", false),
                Arguments.of("C:/Temp/**", WIN, "C:\\Temp\\a", true),
                Arguments.of("/Tmp/**", UBUNTU, "/tmp/a", false),
                Arguments.of("/home/*/cache", UBUNTU, "/home/me/cache", true),
                Arguments.of("/home/*/cache", UBUNTU, "/home/me/x/cache", false),
                Arguments.of("/build-?", UBUNTU, "/build-1", true),
                Arguments.of("/build-?", UBUNTU, "/build-12", false),
                Arguments.of("/a/*.tmp", UBUNTU, "/a/x.tmp", true),
                Arguments.of("/a/**/z", UBUNTU, "/a/z", true),
                Arguments.of("/a/**/z", UBUNTU, "/a/b/c/z", true),
                Arguments.of("/a/**/z", UBUNTU, "/a/b/c", false),
                Arguments.of("\\\\server\\share\\**", WIN, "\\\\SERVER\\Share\\d", true),
                Arguments.of("\\\\server\\share\\**", WIN, "\\\\other\\share\\d", false),
                Arguments.of("**", WIN, "C:\\", true));
    }

    @ParameterizedTest(name = "{0} 와 {1} {2} → {3}")
    @MethodSource("table")
    @DisplayName("glob 은 환경 규칙대로 경로와 맞춘다")
    void matches(String raw, ShellEnvironment env, String target, boolean expected) {
        // given
        PathPattern pattern = pattern(raw);
        // when
        boolean matched = pattern.matches(path(env, target));
        // then
        assertThat(matched).isEqualTo(expected);
    }

    @Test
    @DisplayName("빈 문자열 · 공백뿐인 glob 은 문제다")
    void blank() {
        // when / then
        assertThat(PathPattern.of("").error()).contains(new PatternProblem.Blank());
        assertThat(PathPattern.of("  ").error()).contains(new PatternProblem.Blank());
    }

    @Test
    @DisplayName("상대 경로 glob 은 문제다")
    void relative() {
        // when / then
        assertThat(PathPattern.of("tmp/**").error()).contains(new PatternProblem.NotAbsolute("tmp/**"));
        assertThat(PathPattern.of("*.log").error()).contains(new PatternProblem.NotAbsolute("*.log"));
        assertThat(PathPattern.of("C:foo").error()).contains(new PatternProblem.NotAbsolute("C:foo"));
    }

    @Test
    @DisplayName("원문이 같은 glob 은 같다")
    void equality() {
        // when / then
        assertThat(pattern("/tmp/**")).isEqualTo(pattern("/tmp/**")).hasSameHashCodeAs(pattern("/tmp/**"));
        assertThat(pattern("/tmp/**")).isNotEqualTo(pattern("/tmp/*"));
        assertThat(pattern("/tmp/**").source()).isEqualTo("/tmp/**");
    }
}
