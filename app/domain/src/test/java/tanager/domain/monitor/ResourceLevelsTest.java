package tanager.domain.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 표본 하나의 항목별 경고 판정(FR-MON-03, #187). */
class ResourceLevelsTest {
    private static final Duration INTERVAL = Duration.ofSeconds(2);
    private static final Usage GIB_16 = new Usage(8L << 30, 16L << 30);

    @Test
    @DisplayName("메모리 · 디스크 · WSL 은 비율을 임계값과 비교하고, CPU 는 이동 창으로 판정한다")
    void judgesEachItem() {
        // given
        ResourceSample sample = new ResourceSample(
                Instant.EPOCH,
                0.99,
                8,
                new Usage(90, 100),
                Optional.of(new DiskUsage("C:\\", new Usage(50, 100))),
                Optional.of(new WslUsage("Ubuntu", new Usage(10, 100), new Usage(95, 100))));
        CpuWindow window = CpuWindow.empty().push(0.99, 5);

        // when
        ResourceLevels levels = ResourceLevels.of(sample, window, Thresholds.DEFAULT, INTERVAL);

        // then: CPU 는 한 번뿐이라 아직 NORMAL
        assertThat(levels)
                .isEqualTo(new ResourceLevels(
                        UsageLevel.NORMAL,
                        UsageLevel.WARN,
                        Optional.of(UsageLevel.NORMAL),
                        Optional.of(UsageLevel.NORMAL),
                        Optional.of(UsageLevel.WARN)));
    }

    @Test
    @DisplayName("디스크 · WSL 표본이 없으면 그 판정도 없다")
    void absentItemsHaveNoLevel() {
        // given
        ResourceSample sample = new ResourceSample(Instant.EPOCH, 0.1, 4, GIB_16, Optional.empty(), Optional.empty());

        // when
        ResourceLevels levels = ResourceLevels.of(sample, CpuWindow.empty(), Thresholds.DEFAULT, INTERVAL);

        // then
        assertThat(levels.disk()).isEmpty();
        assertThat(levels.wslMemory()).isEmpty();
        assertThat(levels.wslDisk()).isEmpty();
    }

    @Test
    @DisplayName("표본의 불변식: CPU 0~1 · 코어 1 이상 · 이름은 비어 있지 않다")
    void sampleInvariants() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> new ResourceSample(Instant.EPOCH, 1.5, 4, GIB_16, Optional.empty(), Optional.empty()));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> new ResourceSample(Instant.EPOCH, 0.5, 0, GIB_16, Optional.empty(), Optional.empty()));
        assertThatIllegalArgumentException().isThrownBy(() -> new DiskUsage(" ", GIB_16));
        assertThatIllegalArgumentException().isThrownBy(() -> new WslUsage("", GIB_16, GIB_16));
        assertThatIllegalArgumentException().isThrownBy(() -> new HostUsage(-0.1, 4, GIB_16));
        assertThatIllegalArgumentException().isThrownBy(() -> new HostUsage(0.1, 0, GIB_16));
    }

    @Test
    @DisplayName("호스트 표본과 디스크 · WSL 로 표본 하나를 만든다")
    void sampleFromHost() {
        // given
        HostUsage host = new HostUsage(0.25, 4, GIB_16);

        // when
        ResourceSample sample = ResourceSample.of(Instant.EPOCH, host, Optional.empty(), Optional.empty());

        // then
        assertThat(sample.cpuLoad()).isEqualTo(0.25);
        assertThat(sample.cores()).isEqualTo(4);
        assertThat(sample.memory()).isEqualTo(GIB_16);
    }
}
