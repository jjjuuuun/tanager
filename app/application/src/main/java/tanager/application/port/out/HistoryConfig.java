package tanager.application.port.out;

import java.util.List;

/**
 * 이력 설정(config {@code history}). 불변식: maxEntries 100~100000, exclude 의 항목은 비어 있지 않다. exclude 는 glob 원문이다 — 해석
 * · 검증은 domain({@code HistoryPolicy})이 한다.
 *
 * @param maxEntries 보관할 항목 수 한도
 * @param exclude 기록하지 않을 경로 glob(원문, 파일 순서)
 */
public record HistoryConfig(int maxEntries, List<String> exclude) {
    public static final int MIN_ENTRIES = 100;
    public static final int MAX_ENTRIES = 100_000;

    private static final HistoryConfig DEFAULTS = new HistoryConfig(2000, List.of("/tmp/**", "**/node_modules/**"));

    public HistoryConfig {
        Ranges.require("maxEntries", maxEntries, MIN_ENTRIES, MAX_ENTRIES);
        exclude = List.copyOf(exclude);
        if (exclude.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("exclude 에 빈 glob 이 있다");
        }
    }

    /** SDD §9.3 의 기본값: 2000 항목, {@code /tmp/**} 와 {@code node_modules} 아래 제외. */
    public static HistoryConfig defaults() {
        return DEFAULTS;
    }
}
