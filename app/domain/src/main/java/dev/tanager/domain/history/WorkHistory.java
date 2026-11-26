package dev.tanager.domain.history;

import dev.tanager.domain.shared.Result;
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

/**
 * 작업 경로 이력 애그리거트. 키는 {@link WorkspacePath}(환경별 비교 규칙)이고 경로마다 항목 하나다. 모든 연산은 새 {@code
 * WorkHistory} 를 돌려준다.
 *
 * <p>한도: {@link #recordVisit} · {@link #restore} 직후 항목 수 ≤ max(한도, 고정 수 + 1) — 고정 항목과 방금 방문한 항목은 한도를
 * 넘어도 지우지 않는다. {@link #pin} · {@link #hide} 는 지우지 않는다(고정을 풀자마자 항목이 사라지지 않게). 다음 방문 때 다시 맞춘다.
 */
public record WorkHistory(Map<WorkspacePath, VisitedDirectory> entries, HistoryPolicy policy) {

    public WorkHistory {
        Objects.requireNonNull(policy, "policy");
        entries = Map.copyOf(entries);
        for (var e : entries.entrySet()) {
            if (!e.getKey().equals(e.getValue().path())) {
                throw new IllegalArgumentException(
                        "키와 항목 경로가 다르다: " + e.getKey() + " ≠ " + e.getValue().path());
            }
        }
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
        Map<WorkspacePath, VisitedDirectory> merged = new LinkedHashMap<>();
        for (VisitedDirectory v : entries) {
            merged.merge(v.path(), v, VisitedDirectory::mergedWith);
        }
        return new WorkHistory(evict(merged, policy, Optional.empty()), policy);
    }

    /** {@code path} 방문을 기록한다. 한도를 넘으면 고정 아닌 항목 중 가장 오래 전에 방문한 것부터 지운다(방금 방문한 것은 남김). */
    public WorkHistory recordVisit(WorkspacePath path, Instant at) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(at, "at");
        Map<WorkspacePath, VisitedDirectory> next = new HashMap<>(entries);
        next.merge(path, VisitedDirectory.firstVisit(path, at), (old, fresh) -> old.visitedAgain(at));
        return new WorkHistory(evict(next, policy, Optional.of(path)), policy);
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
        return Result.ok(new WorkHistory(next, policy));
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
        return Result.ok(new WorkHistory(next, policy));
    }

    // 지울 순서: 최근 방문이 이른 것 → 처음 방문이 이른 것 → 경로 문자열. 순회 순서와 무관하게 결정적이다.
    private static final Comparator<VisitedDirectory> EVICTION_ORDER = Comparator.comparing(
                    VisitedDirectory::lastVisited)
            .thenComparing(VisitedDirectory::firstVisited)
            .thenComparing(v -> v.path().path());

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
