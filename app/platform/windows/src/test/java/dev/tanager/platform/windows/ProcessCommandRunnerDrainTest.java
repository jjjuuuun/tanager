package dev.tanager.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProcessCommandRunnerDrainTest {

    @Test
    @DisplayName("남은 시간이 넉넉하면 그만큼 출력을 기다린다")
    void waitsRemainingTime() {
        assertThat(ProcessCommandRunner.drainWait(Duration.ofSeconds(3), Duration.ofSeconds(1)))
                .isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("마감 직전이나 마감 뒤에 끝나도 출력을 최소 200ms 는 기다린다")
    void keepsMinimumGrace() {
        assertThat(ProcessCommandRunner.drainWait(Duration.ofSeconds(3), Duration.ofMillis(2990)))
                .isEqualTo(Duration.ofMillis(200));
        assertThat(ProcessCommandRunner.drainWait(Duration.ofSeconds(3), Duration.ofSeconds(4)))
                .isEqualTo(Duration.ofMillis(200));
    }
}
