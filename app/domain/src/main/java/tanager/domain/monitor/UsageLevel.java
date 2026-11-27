package tanager.domain.monitor;

/** 상태줄 항목의 경고 단계(FR-MON-03). */
public enum UsageLevel {
    NORMAL,
    /** 임계값 이상 — 경고색. */
    WARN;

    /** {@code ratio} 가 {@code threshold} 이상이면 {@link #WARN}. */
    static UsageLevel of(double ratio, double threshold) {
        return ratio >= threshold ? WARN : NORMAL;
    }
}
