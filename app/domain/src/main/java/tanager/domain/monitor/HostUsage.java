package tanager.domain.monitor;

import java.util.Objects;

/**
 * 호스트 CPU · 메모리 표본 하나(FR-MON-01).
 *
 * <p>불변식: 0 ≤ cpuLoad ≤ 1, cores ≥ 1.
 */
public record HostUsage(double cpuLoad, int cores, Usage memory) {

    public HostUsage {
        CpuWindow.requireLoad(cpuLoad);
        if (cores < 1) {
            throw new IllegalArgumentException("코어 수는 1 이상이어야 한다: " + cores);
        }
        Objects.requireNonNull(memory, "memory");
    }
}
