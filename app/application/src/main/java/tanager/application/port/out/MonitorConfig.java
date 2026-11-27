package tanager.application.port.out;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 리소스 표시 설정(config {@code monitor}, 임계값은 파일에서 {@code monitor.thresholds} 아래). 불변식: intervalSec 1~10, 임계값 0 초과
 * 1 이하, cpuSustainSec ≥ 1. {@code domain.monitor} 의 임계값으로 바꾸는 일은 리소스 표시 이슈가 한다.
 *
 * @param enabled 상태줄에 리소스를 보이는가
 * @param intervalSec 표본 간격(초)
 * @param items 보일 항목(열거 순서로 돈다)
 * @param cpu CPU 경고 비율
 * @param cpuSustainSec CPU 가 이 시간(초) 넘게 임계값 위에 있어야 경고한다
 * @param memory 메모리 경고 비율
 * @param disk 디스크 경고 비율
 */
public record MonitorConfig(
        boolean enabled,
        int intervalSec,
        Set<MonitorItem> items,
        double cpu,
        int cpuSustainSec,
        double memory,
        double disk) {
    public static final int MIN_INTERVAL_SEC = 1;
    public static final int MAX_INTERVAL_SEC = 10;
    public static final int MIN_CPU_SUSTAIN_SEC = 1;
    /** 임계값의 위 경계(포함). 아래 경계 0 은 포함하지 않는다. */
    public static final double MAX_THRESHOLD = 1.0;

    private static final MonitorConfig DEFAULTS =
            new MonitorConfig(true, 2, EnumSet.allOf(MonitorItem.class), 0.90, 10, 0.85, 0.90);

    public MonitorConfig {
        Ranges.require("intervalSec", intervalSec, MIN_INTERVAL_SEC, MAX_INTERVAL_SEC);
        items = Collections.unmodifiableSet(
                items.isEmpty() ? EnumSet.noneOf(MonitorItem.class) : EnumSet.copyOf(items));
        requireThreshold("cpu", cpu);
        Ranges.require("cpuSustainSec", cpuSustainSec, MIN_CPU_SUSTAIN_SEC, Integer.MAX_VALUE);
        requireThreshold("memory", memory);
        requireThreshold("disk", disk);
    }

    /** {@code value} 를 임계값으로 쓸 수 있는가(0 초과 1 이하). */
    public static boolean isThreshold(double value) {
        return value > 0 && value <= MAX_THRESHOLD;
    }

    private static void requireThreshold(String name, double value) {
        if (!isThreshold(value)) {
            throw new IllegalArgumentException(name + " 임계값은 0 초과 1 이하여야 한다: " + value);
        }
    }

    /** SDD §9.3 의 기본값: 켜짐 · 2초 · 네 항목 모두 · CPU 0.90(10초) · 메모리 0.85 · 디스크 0.90. */
    public static MonitorConfig defaults() {
        return DEFAULTS;
    }
}
