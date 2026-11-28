package tanager.domain.history;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
import tanager.domain.shared.Result;

/**
 * 작업 경로 이력 애그리거트. 키는 {@link WorkspacePath}(환경별 비교 규칙)이고 경로마다 항목 하나다. 모든 연산은 새 {@code
 * WorkHistory} 를 돌려준다.
 *
 * <p>한도: {@link #recordVisit} · {@link #restore} 직후 항목 수 ≤ max(한도, 고정 수 + 1), {@link #withPolicy} · {@link #importVisits} 직후 ≤ max(한도, 고정
 * 수) — 고정 항목과 방금 방문한 항목은 한도를 넘어도 지우지 않는다. {@link #pin} · {@link #hide} 는 지우지 않는다(고정을 풀자마자 항목이
 * 사라지지 않게). 다음 방문 때 다시 맞춘다.
 *
 * <p>제외 규칙(FR-HIS-09): 제외 경로 방문은 기록하지 않는다. {@link #restore} · {@link #withPolicy} 는 제외 규칙에 맞는 고정 안 된 항목을
 * 지운다. 고정 항목은 사용자가 고른 것이라 남긴다.
 */
public record WorkHistory(
        Map<WorkspacePath, VisitedDirectory> entries, HistoryPolicy policy, Map<ImportOrigin, Instant> importedUntil) {

    public WorkHistory {
        Objects.requireNonNull(policy, "policy");
        entries = Map.copyOf(entries);
        importedUntil = Map.copyOf(importedUntil);
        for (var e : entries.entrySet()) {
            if (!e.getKey().equals(e.getValue().path())) {
                throw new IllegalArgumentException(
                        "키와 항목 경로가 다르다: " + e.getKey() + " ≠ " + e.getValue().path());
            }
        }
    }

    /** 가져온 적 없는 이력. */
    public WorkHistory(Map<WorkspacePath, VisitedDirectory> entries, HistoryPolicy policy) {
        this(entries, policy, Map.of());
    }

    /** 빈 이력. */
    public static WorkHistory empty(HistoryPolicy policy) {
        return new WorkHistory(Map.of(), policy);
    }

    /**
     * 저장된 항목들로 이력을 만든다. 같은 키(예: Windows 에서 대소문자만 다른 경로)는 합친다 —
     * count 합 · first 최소 · last 최대 · 고정 OR(고정이면 숨김 해제) · 경로 표기와 별칭은 먼저 나온 것.
     */
    public static WorkHistory restore(Collection<VisitedDirectory> entries, HistoryPolicy policy) {
        return restore(entries, policy, Map.of());
    }

    /** {@link #restore(Collection, HistoryPolicy)} 에 저장된 가져오기 워터마크를 더한다. */
    public static WorkHistory restore(
            Collection<VisitedDirectory> entries, HistoryPolicy policy, Map<ImportOrigin, Instant> importedUntil) {
        Map<WorkspacePath, VisitedDirectory> merged = new LinkedHashMap<>();
        for (VisitedDirectory v : entries) {
            merged.merge(v.path(), v, VisitedDirectory::mergedWith);
        }
        return new WorkHistory(evict(withoutExcluded(merged, policy), policy, Optional.empty()), policy, importedUntil);
    }

    /**
     * {@code path} 방문을 기록한다. 한도를 넘으면 고정 아닌 항목 중 가장 오래 전에 방문한 것부터 지운다(방금 방문한 것은 남김). 제외 경로면
     * 기록하지 않고 이 인스턴스를 그대로 돌려준다.
     */
    public WorkHistory recordVisit(WorkspacePath path, Instant at) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(at, "at");
        if (policy.excludes(path)) {
            return this;
        }
        Map<WorkspacePath, VisitedDirectory> next = new HashMap<>(entries);
        next.merge(path, VisitedDirectory.firstVisit(path, at), (old, fresh) -> old.visitedAgain(at));
        return new WorkHistory(evict(next, policy, Optional.of(path)), policy, importedUntil);
    }

    /** 정책을 바꾼다. 제외 규칙에 맞는 고정 안 된 항목을 지우고, 새 한도를 넘으면 고정 안 된 항목을 오래된 순으로 지운다. */
    public WorkHistory withPolicy(HistoryPolicy next) {
        Objects.requireNonNull(next, "next");
        return new WorkHistory(evict(withoutExcluded(entries, next), next, Optional.empty()), next, importedUntil);
    }

    /**
     * 가져온 방문을 합친다(FR-TK-09). 시작할 때마다 다시 돌므로 같은 배치를 두 번 적용해도 결과가 같다.
     *
     * <ul>
     *   <li>제외 규칙에 맞는 경로 · 배치와 다른 환경의 경로는 건너뛴다. 배치 안의 같은 경로는 기간을 합쳐 하나로 본다.
     *   <li>있는 항목: 출처를 더하고 first = min, last = max. 방문 횟수 · 고정 · 별칭 · 숨김은 그대로.
     *   <li>없는 항목: lastSeen 이 이 (출처, 환경)의 워터마크보다 늦을 때만 만든다(횟수 1). 워터마크가 없으면 모두 만든다 — 사용자가 지운 경로는
     *       그 뒤에 새 대화가 생길 때만 돌아온다.
     *   <li>워터마크 = max(이전 워터마크, scannedAt, 배치의 가장 늦은 lastSeen). 줄지 않는다 — 시계가 뒤로 가거나 기록 시각이 scannedAt 보다
     *       늦어도 지운 경로가 되살아나지 않게.
     *   <li>한도를 넘으면 고정 아닌 항목을 오래된 순으로 지운다.
     * </ul>
     */
    public WorkHistory importVisits(ImportBatch batch) {
        Objects.requireNonNull(batch, "batch");
        ImportOrigin origin = batch.origin();
        Optional<Instant> watermark = importedUntil(origin);
        Instant nextWatermark = watermark.map(w -> latest(w, batch.scannedAt())).orElse(batch.scannedAt());
        Map<WorkspacePath, ImportedVisit> visits = new LinkedHashMap<>();
        for (ImportedVisit visit : batch.visits()) {
            nextWatermark = latest(nextWatermark, visit.lastSeen());
            if (visit.path().env().equals(batch.env()) && !policy.excludes(visit.path())) {
                visits.merge(visit.path(), visit, WorkHistory::widened);
            }
        }
        Map<WorkspacePath, VisitedDirectory> next = new HashMap<>(entries);
        for (ImportedVisit visit : visits.values()) {
            VisitedDirectory current = next.get(visit.path());
            if (current != null) {
                next.put(visit.path(), current.importedAgain(batch.source(), visit));
            } else if (watermark.isEmpty() || visit.lastSeen().isAfter(watermark.get())) {
                next.put(visit.path(), VisitedDirectory.imported(batch.source(), visit));
            }
        }
        Map<ImportOrigin, Instant> nextUntil = new HashMap<>(importedUntil);
        nextUntil.put(origin, nextWatermark);
        return new WorkHistory(evict(next, policy, Optional.empty()), policy, nextUntil);
    }

    /** {@code origin} 을 마지막으로 가져온 시각(워터마크). 가져온 적 없으면 empty. */
    public Optional<Instant> importedUntil(ImportOrigin origin) {
        Objects.requireNonNull(origin, "origin");
        return Optional.ofNullable(importedUntil.get(origin));
    }

    /** 고정하거나 푼다. 고정하면 숨김은 풀린다. */
    public Result<WorkHistory, HistoryError> pin(WorkspacePath path, boolean pinned) {
        return update(path, v -> v.withPinned(pinned));
    }

    /** 별칭을 붙이거나 지운다. empty · 빈 값 · 공백뿐인 값은 지움이다. */
    public Result<WorkHistory, HistoryError> alias(WorkspacePath path, Optional<String> raw) {
        Objects.requireNonNull(raw, "raw");
        if (raw.isEmpty() || raw.get().isBlank()) {
            return update(path, v -> v.withAlias(Optional.empty()));
        }
        if (find(path).isEmpty()) {
            return Result.err(new HistoryError.NotFound(path));
        }
        return Alias.of(raw.get())
                .<HistoryError>mapError(e -> e)
                .flatMap(alias -> update(path, v -> v.withAlias(Optional.of(alias))));
    }

    /** 숨기거나 드러낸다. 숨기면 고정은 풀린다. */
    public Result<WorkHistory, HistoryError> hide(WorkspacePath path, boolean hidden) {
        return update(path, v -> v.withHidden(hidden));
    }

    /** 항목을 지운다. 다시 방문하면 count 1 부터 새로 시작한다. */
    public Result<WorkHistory, HistoryError> forget(WorkspacePath path) {
        Objects.requireNonNull(path, "path");
        if (!entries.containsKey(path)) {
            return Result.err(new HistoryError.NotFound(path));
        }
        Map<WorkspacePath, VisitedDirectory> next = new HashMap<>(entries);
        next.remove(path);
        return Result.ok(new WorkHistory(next, policy, importedUntil));
    }

    /** {@code path} 의 항목. */
    public Optional<VisitedDirectory> find(WorkspacePath path) {
        Objects.requireNonNull(path, "path");
        return Optional.ofNullable(entries.get(path));
    }

    private Result<WorkHistory, HistoryError> update(WorkspacePath path, UnaryOperator<VisitedDirectory> change) {
        Objects.requireNonNull(path, "path");
        VisitedDirectory current = entries.get(path);
        if (current == null) {
            return Result.err(new HistoryError.NotFound(path));
        }
        Map<WorkspacePath, VisitedDirectory> next = new HashMap<>(entries);
        next.put(path, change.apply(current));
        return Result.ok(new WorkHistory(next, policy, importedUntil));
    }

    // 배치 안에서 같은 경로의 두 방문 — 경로 표기는 먼저 나온 것
    private static ImportedVisit widened(ImportedVisit first, ImportedVisit later) {
        Instant from = first.firstSeen().isBefore(later.firstSeen()) ? first.firstSeen() : later.firstSeen();
        return new ImportedVisit(first.path(), from, latest(first.lastSeen(), later.lastSeen()));
    }

    private static Instant latest(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    // 지울 순서: 최근 방문이 이른 것 → 처음 방문이 이른 것 → 경로 문자열. 순회 순서와 무관하게 결정적이다.
    private static final Comparator<VisitedDirectory> EVICTION_ORDER = Comparator.comparing(
                    VisitedDirectory::lastVisited)
            .thenComparing(VisitedDirectory::firstVisited)
            .thenComparing(v -> v.path().path());

    private static Map<WorkspacePath, VisitedDirectory> withoutExcluded(
            Map<WorkspacePath, VisitedDirectory> entries, HistoryPolicy policy) {
        if (policy.exclude().isEmpty()) {
            return entries;
        }
        Map<WorkspacePath, VisitedDirectory> out = new HashMap<>();
        for (VisitedDirectory v : entries.values()) {
            if (v.pinned() || !policy.excludes(v.path())) {
                out.put(v.path(), v);
            }
        }
        return out;
    }

    // 후보를 한 번 정렬해 앞에서부터 지운다 — 큰 저장 파일을 불러와도 O(n log n).
    private static Map<WorkspacePath, VisitedDirectory> evict(
            Map<WorkspacePath, VisitedDirectory> entries, HistoryPolicy policy, Optional<WorkspacePath> keep) {
        int excess = entries.size() - policy.maxEntries();
        if (excess <= 0) {
            return entries;
        }
        List<VisitedDirectory> candidates = entries.values().stream()
                .filter(v -> !v.pinned() && !keep.equals(Optional.of(v.path())))
                .sorted(EVICTION_ORDER)
                .limit(excess)
                .toList();
        Map<WorkspacePath, VisitedDirectory> out = new HashMap<>(entries);
        for (VisitedDirectory v : candidates) {
            out.remove(v.path());
        }
        return out;
    }
}
