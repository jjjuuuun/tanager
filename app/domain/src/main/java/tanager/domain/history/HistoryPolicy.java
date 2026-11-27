package tanager.domain.history;

import java.util.List;
import java.util.Objects;

/**
 * 이력 보관 규칙(FR-HIS-09). 불변식: maxEntries ≥ 1. 고정 항목은 한도를 넘어도, 제외 규칙에 맞아도 지우지 않는다 — 사용자가 직접 고른
 * 것이다.
 *
 * @param maxEntries 보관할 항목 수 한도
 * @param exclude 기록하지 않을 경로 glob(설정 순서)
 */
public record HistoryPolicy(int maxEntries, List<PathPattern> exclude) {

    /** 기본 한도 2000 항목, 제외 규칙 없음. 설정의 제외 규칙은 첫 설정 반영 때 들어온다. */
    public static final HistoryPolicy DEFAULT = new HistoryPolicy(2000);

    public HistoryPolicy {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries 는 1 이상이어야 한다: " + maxEntries);
        }
        exclude = List.copyOf(exclude);
    }

    /** 제외 규칙 없는 정책. */
    public HistoryPolicy(int maxEntries) {
        this(maxEntries, List.of());
    }

    /** {@code path} 가 제외 규칙 중 하나에 맞는가. */
    public boolean excludes(WorkspacePath path) {
        Objects.requireNonNull(path, "path");
        for (PathPattern pattern : exclude) {
            if (pattern.matches(path)) {
                return true;
            }
        }
        return false;
    }
}
