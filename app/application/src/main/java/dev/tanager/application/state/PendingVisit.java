package dev.tanager.application.state;

import dev.tanager.domain.history.WorkspacePath;
import java.time.Instant;
import java.util.Objects;

/** 이력을 불러오기 전에 기록된 방문. 불러온 이력 위에 같은 시각으로 다시 적용한다(ADR-0013). */
public record PendingVisit(WorkspacePath path, Instant at) {
    public PendingVisit {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(at, "at");
    }
}
