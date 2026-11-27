package tanager.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
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

    @Test
    @DisplayName("자식은 끝났지만 손자가 출력 파이프를 붙잡고 있으면 시간 초과 + 작은 여유 안에 TimedOut 이다")
    void grandchildHoldingPipeTimesOutPromptly() {
        // given
        Duration timeout = Duration.ofMillis(300);
        long start = System.nanoTime();
        // when
        CommandOutcome r = runner.run(List.of("cmd.exe", "/c", "start", "/b", "ping", "-n", "5", "127.0.0.1"), timeout);
        // then
        assertThat(r).isInstanceOf(CommandOutcome.TimedOut.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(timeout.plusMillis(1500));
    }

    @Test
    @DisplayName("시간 초과면 손자 프로세스까지 끝낸다")
    void timeoutKillsGrandchildren() {
        // given
        Instant before = Instant.now();
        // when
        CommandOutcome r =
                runner.run(List.of("cmd.exe", "/c", "ping", "-n", "10", "127.0.0.1"), Duration.ofMillis(500));
        // then
        assertThat(r).isInstanceOf(CommandOutcome.TimedOut.class);
        List<ProcessHandle> pings = ProcessHandle.allProcesses()
                .filter(p -> p.info()
                        .command()
                        .map(c -> c.toLowerCase(Locale.ROOT).endsWith("ping.exe"))
                        .orElse(false))
                .filter(p ->
                        p.info().startInstant().map(s -> !s.isBefore(before)).orElse(false))
                .toList();
        for (ProcessHandle ping : pings) {
            assertThat(ping.onExit()).succeedsWithin(Duration.ofSeconds(2));
        }
    }

    @Test
    @DisplayName("인터럽트된 상태로 부르면 TimedOut 이고 인터럽트 플래그가 남는다")
    void interruptedKeepsFlag() {
        // given
        Thread.currentThread().interrupt();
        try {
            // when
            CommandOutcome r = runner.run(List.of("ping", "-n", "5", "127.0.0.1"), Duration.ofSeconds(5));
            // then
            assertThat(r).isInstanceOf(CommandOutcome.TimedOut.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
