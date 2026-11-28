package tanager.ui.measure;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import tanager.domain.shared.PaneId;

/**
 * 입력 반응 표본의 분위수(밀리초, 아래는 버림). 분위수는 최근접 순위법이다 — 표본 3개의 p50 은 둘째 값.
 *
 * @param count 표본 수(1 이상)
 */
record LatencyStats(int count, long p50, long p95, long p99, long max) {

    LatencyStats {
        if (count < 1) {
            throw new IllegalArgumentException("표본이 없다: " + count);
        }
    }

    /** 나노초 표본에서. 비어 있으면 empty. */
    static Optional<LatencyStats> of(List<Long> nanos) {
        if (nanos.isEmpty()) {
            return Optional.empty();
        }
        long[] millis = new long[nanos.size()];
        for (int i = 0; i < millis.length; i++) {
            millis[i] = TimeUnit.NANOSECONDS.toMillis(nanos.get(i));
        }
        Arrays.sort(millis);
        return Optional.of(new LatencyStats(
                millis.length, rank(millis, 50), rank(millis, 95), rank(millis, 99), millis[millis.length - 1]));
    }

    /** 예: {@code input-latency pane=p1 n=3 p50=10ms p95=40ms p99=40ms max=40ms}. */
    String line(PaneId pane) {
        return "input-latency pane=" + pane.value() + " n=" + count + " p50=" + p50 + "ms p95=" + p95 + "ms p99=" + p99
                + "ms max=" + max + "ms";
    }

    private static long rank(long[] sorted, int percent) {
        int rank = (int) Math.ceil(percent / 100.0 * sorted.length);
        return sorted[Math.max(0, rank - 1)];
    }
}
