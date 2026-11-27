package tanager.domain.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 경고 임계값(FR-MON-03, #187). */
class ThresholdsTest {

    @Test
    @DisplayName("기본값은 CPU 90% 10초 · 메모리 85% · 디스크 90% 다")
    void defaults() {
        assertThat(Thresholds.DEFAULT).isEqualTo(new Thresholds(0.90, Duration.ofSeconds(10), 0.85, 0.90));
    }

    @Test
    @DisplayName("비율은 0 초과 1 이하, 지속 시간은 0 보다 커야 한다")
    void invariants() {
        Duration ten = Duration.ofSeconds(10);
        assertThatIllegalArgumentException().isThrownBy(() -> new Thresholds(0, ten, 0.5, 0.5));
        assertThatIllegalArgumentException().isThrownBy(() -> new Thresholds(0.5, ten, 1.01, 0.5));
        assertThatIllegalArgumentException().isThrownBy(() -> new Thresholds(0.5, ten, 0.5, -0.1));
        assertThatIllegalArgumentException().isThrownBy(() -> new Thresholds(0.5, Duration.ZERO, 0.5, 0.5));
        assertThat(new Thresholds(1.0, Duration.ofSeconds(1), 1.0, 1.0).cpu()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("지속 판정에 필요한 표본 수는 ceil(지속 시간 ÷ 주기)다")
    void samplesNeeded() {
        Thresholds t = Thresholds.DEFAULT;
        assertThat(t.cpuSamplesNeeded(Duration.ofSeconds(2))).isEqualTo(5);
        assertThat(t.cpuSamplesNeeded(Duration.ofSeconds(3))).isEqualTo(4);
        assertThat(t.cpuSamplesNeeded(Duration.ofSeconds(10))).isEqualTo(1);
        assertThat(t.cpuSamplesNeeded(Duration.ofSeconds(20))).isEqualTo(1);
    }

    @Test
    @DisplayName("주기는 0 보다 커야 한다")
    void intervalMustBePositive() {
        assertThatIllegalArgumentException().isThrownBy(() -> Thresholds.DEFAULT.cpuSamplesNeeded(Duration.ZERO));
    }
}
