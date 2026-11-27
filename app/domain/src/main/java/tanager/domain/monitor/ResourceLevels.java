package tanager.domain.monitor;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** 표본 하나의 항목별 경고 단계(FR-MON-03). 표본에 없는 항목은 단계도 없다. */
public record ResourceLevels(
        UsageLevel cpu,
        UsageLevel memory,
        Optional<UsageLevel> disk,
        Optional<UsageLevel> wslMemory,
        Optional<UsageLevel> wslDisk) {

    public ResourceLevels {
        Objects.requireNonNull(cpu, "cpu");
        Objects.requireNonNull(memory, "memory");
        Objects.requireNonNull(disk, "disk");
        Objects.requireNonNull(wslMemory, "wslMemory");
        Objects.requireNonNull(wslDisk, "wslDisk");
    }

    /** CPU 는 {@code window}(이 표본을 넣은 창)로, 나머지는 표본의 비율로 판정한다. */
    public static ResourceLevels of(ResourceSample s, CpuWindow window, Thresholds t, Duration interval) {
        return new ResourceLevels(
                window.level(t, interval),
                s.memory().level(t.memory()),
                s.disk().map(d -> d.usage().level(t.disk())),
                s.wsl().map(w -> w.vmMemory().level(t.memory())),
                s.wsl().map(w -> w.rootDisk().level(t.disk())));
    }
}
