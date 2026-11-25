package dev.tanager.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@Tag("windows")
@EnabledOnOs(OS.WINDOWS)
class ProcessCommandRunnerTest {
    private final ProcessCommandRunner runner = new ProcessCommandRunner();

    @Test
    @DisplayName("정상 종료하면 종료 코드와 표준 출력을 돌려준다")
    void exited() {
        CommandOutcome r = runner.run(List.of("cmd.exe", "/c", "echo", "hi"), Duration.ofSeconds(5));
        assertThat(r).isInstanceOfSatisfying(CommandOutcome.Exited.class, e -> {
            assertThat(e.code()).isZero();
            assertThat(new String(e.stdout(), StandardCharsets.UTF_8)).contains("hi");
        });
    }

    @Test
    @DisplayName("없는 실행 파일은 NotStarted 다")
    void notStarted() {
        assertThat(runner.run(List.of("no-such-tanager-exe.exe"), Duration.ofSeconds(2)))
                .isInstanceOf(CommandOutcome.NotStarted.class);
    }

    @Test
    @DisplayName("시간이 지나면 TimedOut 이다")
    void timedOut() {
        assertThat(runner.run(List.of("ping", "-n", "5", "127.0.0.1"), Duration.ofMillis(200)))
                .isInstanceOf(CommandOutcome.TimedOut.class);
    }
}
