package tanager.domain.monitor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * CPU 지속 판정용 이동 창(순수, FR-MON-03). 최근 {@code ceil(cpuSustain ÷ interval)} 개가 모두 임계값 이상이면 경고다 — 잠깐 치솟은 부하로
 * 경고색이 깜빡이지 않게.
 *
 * <p>불변식: 값은 모두 0~1, 오래된 것부터.
 */
public record CpuWindow(List<Double> recent) {
    private static final CpuWindow EMPTY = new CpuWindow(List.of());

    public CpuWindow {
        recent = List.copyOf(recent);
        for (double load : recent) {
            requireLoad(load);
        }
    }

    /** 표본이 없는 창. */
    public static CpuWindow empty() {
        return EMPTY;
    }

    /** {@code load} 를 더하고 최근 {@code max} 개만 남긴 새 창. */
    public CpuWindow push(double load, int max) {
        requireLoad(load);
        if (max < 1) {
            throw new IllegalArgumentException("창 크기는 1 이상이어야 한다: " + max);
        }
        List<Double> next = new ArrayList<>(recent);
        next.add(load);
        return new CpuWindow(next.subList(Math.max(0, next.size() - max), next.size()));
    }

    /** 최근 {@link Thresholds#cpuSamplesNeeded} 개가 모두 {@code t.cpu()} 이상이면 {@link UsageLevel#WARN}. 모자라면 NORMAL. */
    public UsageLevel level(Thresholds t, Duration interval) {
        int needed = t.cpuSamplesNeeded(interval);
        if (recent.size() < needed) {
            return UsageLevel.NORMAL;
        }
        for (double load : recent.subList(recent.size() - needed, recent.size())) {
            if (UsageLevel.of(load, t.cpu()) == UsageLevel.NORMAL) {
                return UsageLevel.NORMAL;
            }
        }
        return UsageLevel.WARN;
    }

    static void requireLoad(double load) {
        if (!(load >= 0 && load <= 1)) {
            throw new IllegalArgumentException("CPU 사용률은 0~1 이어야 한다: " + load);
        }
    }
}
