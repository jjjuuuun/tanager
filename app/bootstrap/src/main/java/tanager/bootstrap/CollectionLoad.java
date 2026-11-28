package tanager.bootstrap;

import java.util.Locale;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

/**
 * 리소스 수집 자체의 부하 한 구간(NFR-10, #222). 불변이다 — 기록은 새 값을 돌려준다. 시각 · CPU 시간은 나노초.
 *
 * @param windowStart 이 구간의 시작 시각
 * @param tasks 이 구간에 끝난 수집 일 수
 * @param cpuNanos 수집 스레드가 쓴 CPU 시간의 합
 * @param cpuMissing CPU 시간을 재지 못한 일이 있었다(스레드 CPU 시간 미지원)
 * @param wslCalls 이 구간의 {@code wsl.exe} 호출 수
 * @param lastWsl 마지막 {@code wsl.exe} 호출 시각 — 앞 구간의 것일 수 있다
 * @param minWslGap 이 구간에 잰 호출 간격의 최솟값
 */
record CollectionLoad(
        long windowStart,
        int tasks,
        long cpuNanos,
        boolean cpuMissing,
        int wslCalls,
        OptionalLong lastWsl,
        OptionalLong minWslGap) {

    static CollectionLoad startingAt(long now) {
        return new CollectionLoad(now, 0, 0, false, 0, OptionalLong.empty(), OptionalLong.empty());
    }

    /** 수집 일 하나가 CPU {@code cpu} 나노초를 쓰고 끝났다. */
    CollectionLoad collected(long cpu) {
        return new CollectionLoad(windowStart, tasks + 1, cpuNanos + cpu, cpuMissing, wslCalls, lastWsl, minWslGap);
    }

    /** 수집 일 하나가 끝났지만 CPU 시간을 재지 못했다. */
    CollectionLoad collectedWithoutCpu() {
        return new CollectionLoad(windowStart, tasks + 1, cpuNanos, true, wslCalls, lastWsl, minWslGap);
    }

    /** {@code at} 에 {@code wsl.exe} 를 불렀다. */
    CollectionLoad wslCalled(long at) {
        OptionalLong gap = lastWsl.isPresent() ? OptionalLong.of(at - lastWsl.getAsLong()) : OptionalLong.empty();
        OptionalLong min = gap.isEmpty()
                ? minWslGap
                : OptionalLong.of(Math.min(gap.getAsLong(), minWslGap.orElse(Long.MAX_VALUE)));
        return new CollectionLoad(windowStart, tasks, cpuNanos, cpuMissing, wslCalls + 1, OptionalLong.of(at), min);
    }

    /** {@code now} 에 시작하는 다음 구간. 마지막 호출 시각은 넘긴다 — 구간 경계를 넘는 간격도 잰다. */
    CollectionLoad next(long now) {
        return new CollectionLoad(now, 0, 0, false, 0, lastWsl, OptionalLong.empty());
    }

    /**
     * {@code now} 까지의 한 줄. 예: {@code resource-load window=60s tasks=30 cpuCore=0.20% cpuAll=0.03% wslCalls=12
     * wslMinGap=5001ms}. {@code cpuCore} 는 한 코어 대비, {@code cpuAll} 은 {@code cores} 개 전체 대비(작업 관리자와 같은 기준).
     */
    String line(long now, int cores) {
        long window = Math.max(1, now - windowStart);
        String core = cpuMissing ? "unavailable" : percent(cpuNanos * 100.0 / window);
        String all = cpuMissing ? "unavailable" : percent(cpuNanos * 100.0 / window / Math.max(1, cores));
        String gap = minWslGap.isPresent() ? TimeUnit.NANOSECONDS.toMillis(minWslGap.getAsLong()) + "ms" : "-";
        return "resource-load window=" + TimeUnit.NANOSECONDS.toSeconds(window) + "s tasks=" + tasks + " cpuCore="
                + core + " cpuAll=" + all + " wslCalls=" + wslCalls + " wslMinGap=" + gap;
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.2f%%", value);
    }
}
