package tanager.domain.monitor;

/**
 * 사용량 하나(바이트). 메모리 · 디스크가 쓴다(SDD §5.6).
 *
 * <p>불변식: total &gt; 0, 0 ≤ used ≤ total.
 */
public record Usage(long used, long total) {

    public Usage {
        if (total <= 0) {
            throw new IllegalArgumentException("전체는 0 보다 커야 한다: " + total);
        }
        if (used < 0 || used > total) {
            throw new IllegalArgumentException("사용량은 0 이상 전체 이하여야 한다: " + used + " / " + total);
        }
    }

    /** 사용 비율(0~1). */
    public double ratio() {
        return (double) used / total;
    }

    /** 비율이 {@code threshold} 이상이면 경고다. */
    public UsageLevel level(double threshold) {
        return UsageLevel.of(ratio(), threshold);
    }
}
