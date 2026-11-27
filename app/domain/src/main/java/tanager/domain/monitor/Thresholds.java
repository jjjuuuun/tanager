package tanager.domain.monitor;

import java.time.Duration;
import java.util.Objects;

/**
 * 경고 임계값(FR-MON-03). CPU 는 {@code cpuSustain} 동안 계속 {@code cpu} 이상이어야 경고다.
 *
 * <p>불변식: 0 &lt; cpu · memory · disk ≤ 1, cpuSustain &gt; 0.
 */
public record Thresholds(double cpu, Duration cpuSustain, double memory, double disk) {
    /** SDD §9.3 의 기본값. */
    public static final Thresholds DEFAULT = new Thresholds(0.90, Duration.ofSeconds(10), 0.85, 0.90);

    public Thresholds {
        requireRatio("cpu", cpu);
        Objects.requireNonNull(cpuSustain, "cpuSustain");
        if (!cpuSustain.isPositive()) {
            throw new IllegalArgumentException("CPU 지속 시간은 0 보다 커야 한다: " + cpuSustain);
        }
        requireRatio("memory", memory);
        requireRatio("disk", disk);
    }

    /** 주기 {@code interval} 로 CPU 지속을 판정하는 데 필요한 연속 표본 수: ceil(cpuSustain ÷ interval), 1 이상. */
    public int cpuSamplesNeeded(Duration interval) {
        Objects.requireNonNull(interval, "interval");
        if (!interval.isPositive()) {
            throw new IllegalArgumentException("주기는 0 보다 커야 한다: " + interval);
        }
        long sustain = cpuSustain.toMillis();
        long step = Math.max(1, interval.toMillis());
        return (int) Math.max(1, Math.ceilDiv(sustain, step));
    }

    private static void requireRatio(String name, double value) {
        if (!(value > 0 && value <= 1)) {
            throw new IllegalArgumentException(name + " 임계값은 0 초과 1 이하여야 한다: " + value);
        }
    }
}
