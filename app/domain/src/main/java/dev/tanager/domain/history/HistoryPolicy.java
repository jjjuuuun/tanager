package dev.tanager.domain.history;

/** 이력 보관 규칙. 불변식: maxEntries ≥ 1. 고정 항목은 한도를 넘어도 지우지 않는다. */
public record HistoryPolicy(int maxEntries) {

    /** 기본 한도 2000 항목. */
    public static final HistoryPolicy DEFAULT = new HistoryPolicy(2000);

    public HistoryPolicy {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries 는 1 이상이어야 한다: " + maxEntries);
        }
    }
}
