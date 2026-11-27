package tanager.application.state;

import java.time.Instant;
import java.util.Objects;
import tanager.domain.history.WorkspacePath;

/** 이력을 불러오기 전에 기록된 방문. 불러온 이력 위에 같은 시각으로 다시 적용한다(ADR-0013). */
public record PendingVisit(WorkspacePath path, Instant at) {
    public PendingVisit {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(at, "at");
    }
}
