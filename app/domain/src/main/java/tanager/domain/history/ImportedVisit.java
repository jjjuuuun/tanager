package tanager.domain.history;

import java.time.Instant;
import java.util.Objects;

/**
 * 가져온 방문 하나 — 어떤 도구가 이 경로를 썼다는 사실과 그 기간(FR-TK-09). 불변식: firstSeen ≤ lastSeen.
 *
 * @param path 도구가 쓴 작업 경로
 * @param firstSeen 그 경로에서 처음 쓴 시각
 * @param lastSeen 그 경로에서 마지막으로 쓴 시각
 */
public record ImportedVisit(WorkspacePath path, Instant firstSeen, Instant lastSeen) {

    public ImportedVisit {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(firstSeen, "firstSeen");
        Objects.requireNonNull(lastSeen, "lastSeen");
        if (firstSeen.isAfter(lastSeen)) {
            throw new IllegalArgumentException("firstSeen 이 lastSeen 보다 늦다: " + firstSeen + " > " + lastSeen);
        }
    }
}
