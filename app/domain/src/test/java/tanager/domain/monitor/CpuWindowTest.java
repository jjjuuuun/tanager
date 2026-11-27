package tanager.domain.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CPU 지속 판정 이동 창(FR-MON-03, #187). */
class CpuWindowTest {
    private static final Duration INTERVAL = Duration.ofSeconds(2);
    private static final int MAX = Thresholds.DEFAULT.cpuSamplesNeeded(INTERVAL);

    private static CpuWindow pushAll(double... loads) {
        CpuWindow window = CpuWindow.empty();
        for (double load : loads) {
            window = window.push(load, MAX);
        }
        return window;
    }

    @Test
    @DisplayName("주기 2초 · 지속 10초에서 0.9 이상이 5개 연속이면 WARN 이다")
    void fiveConsecutiveHighIsWarn() {
        // when
        CpuWindow window = pushAll(0.9, 0.95, 1.0, 0.92, 0.9);

        // then
        assertThat(window.level(Thresholds.DEFAULT, INTERVAL)).isEqualTo(UsageLevel.WARN);
    }

    @Test
    @DisplayName("0.9 이상이 4개뿐이면 NORMAL 이다")
    void fourHighIsNormal() {
        assertThat(pushAll(0.9, 0.95, 1.0, 0.92).level(Thresholds.DEFAULT, INTERVAL))
                .isEqualTo(UsageLevel.NORMAL);
    }

    @Test
    @DisplayName("중간에 0.5 가 있으면 NORMAL 이다")
    void dipInTheMiddleIsNormal() {
        assertThat(pushAll(0.9, 0.95, 0.5, 0.92, 0.9).level(Thresholds.DEFAULT, INTERVAL))
                .isEqualTo(UsageLevel.NORMAL);
    }

    @Test
    @DisplayName("오래된 낮은 값은 창에서 밀려난다 — 최근 5개가 모두 높으면 WARN")
    void oldLowValueSlidesOut() {
        // when
        CpuWindow window = pushAll(0.5, 0.9, 0.9, 0.9, 0.9, 0.9);

        // then
        assertThat(window.recent()).hasSize(MAX);
        assertThat(window.level(Thresholds.DEFAULT, INTERVAL)).isEqualTo(UsageLevel.WARN);
    }

    @Test
    @DisplayName("창이 기준보다 길어도(주기를 줄인 뒤) 최근 n 개만 본다")
    void looksOnlyAtTheMostRecent() {
        // given: 주기 1초면 10개가 필요하다
        CpuWindow window = CpuWindow.empty();
        for (int i = 0; i < 10; i++) {
            window = window.push(i == 0 ? 0.1 : 0.95, 10);
        }

        // then: 주기 2초 기준(5개)은 WARN, 1초 기준(10개)은 NORMAL
        assertThat(window.level(Thresholds.DEFAULT, INTERVAL)).isEqualTo(UsageLevel.WARN);
        assertThat(window.level(Thresholds.DEFAULT, Duration.ofSeconds(1))).isEqualTo(UsageLevel.NORMAL);
    }

    @Test
    @DisplayName("값은 0~1 이고 max 는 1 이상이어야 한다. 비어 있으면 NORMAL 이다")
    void invariants() {
        assertThatIllegalArgumentException().isThrownBy(() -> CpuWindow.empty().push(1.1, 5));
        assertThatIllegalArgumentException().isThrownBy(() -> CpuWindow.empty().push(0.5, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new CpuWindow(List.of(-0.1)));
        assertThat(CpuWindow.empty().level(Thresholds.DEFAULT, INTERVAL)).isEqualTo(UsageLevel.NORMAL);
    }
}
