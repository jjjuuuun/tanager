package dev.tanager.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tanager.application.port.out.LaunchCommand.EnvNames;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LaunchCommandTest {

    @Test
    @DisplayName("argv 가 비어 있으면 거부한다")
    void rejectsEmptyArgv() {
        assertThatThrownBy(() -> new LaunchCommand(List.of(), Optional.empty(), Map.of(), EnvNames.CASE_SENSITIVE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("argv[0] 이 공백이면 거부한다")
    void rejectsBlankExecutable() {
        assertThatThrownBy(() ->
                        new LaunchCommand(List.of("  ", "x"), Optional.empty(), Map.of(), EnvNames.CASE_SENSITIVE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("argv 에 null 원소가 있으면 거부한다")
    void rejectsNullElement() {
        assertThatThrownBy(() -> new LaunchCommand(
                        Arrays.asList("sh", null), Optional.empty(), Map.of(), EnvNames.CASE_SENSITIVE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("환경 변수 이름이 공백이면 거부한다")
    void rejectsBlankEnvironmentKey() {
        assertThatThrownBy(() ->
                        new LaunchCommand(List.of("sh"), Optional.empty(), Map.of(" ", "v"), EnvNames.CASE_SENSITIVE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("만든 뒤 입력 리스트 · 맵을 바꿔도 명령은 변하지 않는다")
    void defensivelyCopiesInputs() {
        // given
        List<String> argv = new ArrayList<>(List.of("sh", "-i"));
        Map<String, String> env = new HashMap<>(Map.of("A", "1"));
        LaunchCommand command = new LaunchCommand(argv, Optional.empty(), env, EnvNames.CASE_SENSITIVE);

        // when
        argv.add("-x");
        env.put("B", "2");

        // then
        assertThat(command.argv()).containsExactly("sh", "-i");
        assertThat(command.environment()).containsExactly(Map.entry("A", "1"));
        assertThatThrownBy(() -> command.argv().add("y")).isInstanceOf(UnsupportedOperationException.class);
    }

    private static LaunchCommand withDir(String dir) {
        return new LaunchCommand(List.of("sh"), Optional.of(dir), Map.of(), EnvNames.CASE_SENSITIVE);
    }

    private static LaunchCommand withEnv(String name, String value) {
        return new LaunchCommand(List.of("sh"), Optional.empty(), Map.of(name, value), EnvNames.CASE_SENSITIVE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"foo\\bar", "", "  ", "./x", "C:work", "~", "bar/baz"})
    @DisplayName("상대 · 빈 · 공백 작업 디렉터리는 거부한다")
    void rejectsNonAbsoluteWorkingDirectory(String dir) {
        assertThatThrownBy(() -> withDir(dir)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/home/me", "C:\\work", "d:\\한글 폴더 #1", "\\\\wsl.localhost\\Ubuntu\\home"})
    @DisplayName("POSIX · 드라이브 · UNC 절대 경로는 받는다")
    void acceptsLexicallyAbsoluteWorkingDirectory(String dir) {
        assertThat(withDir(dir).workingDirectory()).contains(dir);
    }

    @ParameterizedTest
    @ValueSource(strings = {"A=B", "A\0", "\0", "=", "=C:"})
    @DisplayName("이름에 = 나 NUL 이 있으면 거부한다")
    void rejectsInvalidVariableName(String name) {
        assertThatThrownBy(() -> withEnv(name, "v")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("값에 NUL 이 있으면 거부한다")
    void rejectsNulInValue() {
        assertThatThrownBy(() -> withEnv("A", "x\0y")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("값이 빈 문자열이거나 한글 · 공백이 들어도 받는다")
    void acceptsUnusualValues() {
        assertThat(withEnv("A", "").environment()).containsEntry("A", "");
        assertThat(withEnv("한글", "a b").environment()).containsEntry("한글", "a b");
    }

    @Test
    @DisplayName("envNames 는 null 이면 거부한다")
    void rejectsNullEnvNames() {
        assertThatThrownBy(() -> new LaunchCommand(List.of("sh"), Optional.empty(), Map.of(), null))
                .isInstanceOf(NullPointerException.class);
    }
}
