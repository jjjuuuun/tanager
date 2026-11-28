package tanager.domain.history;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 한 번의 가져오기 — {@code source} 가 {@code env} 에서 쓴 경로들을 {@code scannedAt} 에 읽은 결과(FR-TK-09).
 *
 * @param source 방문을 남긴 도구
 * @param env 읽은 환경. 다른 환경의 경로는 가져올 때 건너뛴다
 * @param scannedAt 읽은 시각
 * @param visits 읽은 방문(같은 경로가 여러 번 있어도 된다)
 */
public record ImportBatch(VisitSource source, ShellEnvironment env, Instant scannedAt, List<ImportedVisit> visits) {

    public ImportBatch {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(env, "env");
        Objects.requireNonNull(scannedAt, "scannedAt");
        visits = List.copyOf(visits);
    }

    /** 이 가져오기의 (출처, 환경). */
    public ImportOrigin origin() {
        return new ImportOrigin(source, env);
    }
}
