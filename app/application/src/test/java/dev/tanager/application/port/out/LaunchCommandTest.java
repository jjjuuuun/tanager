package dev.tanager.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LaunchCommandTest {

    @Test
    @DisplayName("argv 가 비어 있으면 거부한다")
    void rejectsEmptyArgv() {
        assertThatThrownBy(() -> new LaunchCommand(List.of(), Optional.empty(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("argv[0] 이 공백이면 거부한다")
    void rejectsBlankExecutable() {
        assertThatThrownBy(() -> new LaunchCommand(List.of("  ", "x"), Optional.empty(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("argv 에 null 원소가 있으면 거부한다")
    void rejectsNullElement() {
        assertThatThrownBy(() -> new LaunchCommand(Arrays.asList("sh", null), Optional.empty(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("환경 변수 이름이 공백이면 거부한다")
    void rejectsBlankEnvironmentKey() {
        assertThatThrownBy(() -> new LaunchCommand(List.of("sh"), Optional.empty(), Map.of(" ", "v")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("만든 뒤 입력 리스트 · 맵을 바꿔도 명령은 변하지 않는다")
    void defensivelyCopiesInputs() {
        // given
        List<String> argv = new ArrayList<>(List.of("sh", "-i"));
        Map<String, String> env = new HashMap<>(Map.of("A", "1"));
        LaunchCommand command = new LaunchCommand(argv, Optional.empty(), env);

        // when
        argv.add("-x");
        env.put("B", "2");

        // then
        assertThat(command.argv()).containsExactly("sh", "-i");
        assertThat(command.environment()).containsExactly(Map.entry("A", "1"));
        assertThatThrownBy(() -> command.argv().add("y")).isInstanceOf(UnsupportedOperationException.class);
    }
}
