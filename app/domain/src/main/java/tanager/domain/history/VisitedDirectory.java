package tanager.domain.history;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 이력의 항목 하나 — 한 환경의 작업 경로와 방문 기록 · 사용자 표시 · 출처(셸, Claude Code …).
 * 불변식: visitCount ≥ 1 · firstVisited ≤ lastVisited · 고정이면서 숨김일 수 없다 · 출처는 비지 않는다(id 순으로 정렬된 불변 집합).
 */
public record VisitedDirectory(
        WorkspacePath path,
        Instant firstVisited,
        Instant lastVisited,
        int visitCount,
        boolean pinned,
        Optional<Alias> alias,
        boolean hidden,
        Set<VisitSource> sources) {

    private static final Comparator<VisitSource> SOURCE_ORDER = Comparator.comparing(VisitSource::id);

    public VisitedDirectory {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(firstVisited, "firstVisited");
        Objects.requireNonNull(lastVisited, "lastVisited");
        Objects.requireNonNull(alias, "alias");
        sources = sorted(sources);
        if (visitCount < 1) {
            throw new IllegalArgumentException("visitCount 는 1 이상이어야 한다: " + visitCount);
        }
        if (firstVisited.isAfter(lastVisited)) {
            throw new IllegalArgumentException(
                    "firstVisited 가 lastVisited 보다 늦다: " + firstVisited + " > " + lastVisited);
        }
        if (pinned && hidden) {
            throw new IllegalArgumentException("고정 항목은 숨길 수 없다: " + path);
        }
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("출처가 비었다: " + path);
        }
    }

    /** 셸로만 방문한 항목. */
    public VisitedDirectory(
            WorkspacePath path,
            Instant firstVisited,
            Instant lastVisited,
            int visitCount,
            boolean pinned,
            Optional<Alias> alias,
            boolean hidden) {
        this(path, firstVisited, lastVisited, visitCount, pinned, alias, hidden, Set.of(VisitSource.SHELL));
    }

    /** {@code source} 가 이 경로를 썼는가(예: 트리의 "Claude 사용" 표시). */
    public boolean usedBy(VisitSource source) {
        Objects.requireNonNull(source, "source");
        return sources.contains(source);
    }

    static VisitedDirectory firstVisit(WorkspacePath path, Instant at) {
        return new VisitedDirectory(path, at, at, 1, false, Optional.empty(), false);
    }

    static VisitedDirectory imported(VisitSource source, ImportedVisit visit) {
        return new VisitedDirectory(
                visit.path(), visit.firstSeen(), visit.lastSeen(), 1, false, Optional.empty(), false, Set.of(source));
    }

    // 시계가 뒤로 가도 lastVisited 는 줄지 않는다.
    VisitedDirectory visitedAgain(Instant at) {
        return new VisitedDirectory(
                path,
                firstVisited,
                latest(lastVisited, at),
                addCapped(visitCount, 1),
                pinned,
                alias,
                hidden,
                withSource(VisitSource.SHELL));
    }

    /** 가져온 방문을 더한다. 출처만 더하고 기간을 넓힌다 — 방문 횟수 · 고정 · 별칭 · 숨김은 그대로. */
    VisitedDirectory importedAgain(VisitSource source, ImportedVisit visit) {
        return new VisitedDirectory(
                path,
                earliest(firstVisited, visit.firstSeen()),
                latest(lastVisited, visit.lastSeen()),
                visitCount,
                pinned,
                alias,
                hidden,
                withSource(source));
    }

    VisitedDirectory withPinned(boolean pinned) {
        return new VisitedDirectory(
                path, firstVisited, lastVisited, visitCount, pinned, alias, pinned ? false : hidden, sources);
    }

    VisitedDirectory withHidden(boolean hidden) {
        return new VisitedDirectory(
                path, firstVisited, lastVisited, visitCount, hidden ? false : pinned, alias, hidden, sources);
    }

    VisitedDirectory withAlias(Optional<Alias> alias) {
        return new VisitedDirectory(path, firstVisited, lastVisited, visitCount, pinned, alias, hidden, sources);
    }

    /** 같은 키의 중복 항목을 합친다. 경로 표기 · 별칭은 이쪽(먼저 나온 것)이 이긴다. 출처는 합집합. */
    VisitedDirectory mergedWith(VisitedDirectory later) {
        boolean mergedPinned = pinned || later.pinned;
        Set<VisitSource> mergedSources = new TreeSet<>(SOURCE_ORDER);
        mergedSources.addAll(sources);
        mergedSources.addAll(later.sources);
        return new VisitedDirectory(
                path,
                earliest(firstVisited, later.firstVisited),
                latest(lastVisited, later.lastVisited),
                addCapped(visitCount, later.visitCount),
                mergedPinned,
                alias.or(later::alias),
                !mergedPinned && (hidden || later.hidden),
                mergedSources);
    }

    private Set<VisitSource> withSource(VisitSource source) {
        if (sources.contains(source)) {
            return sources;
        }
        Set<VisitSource> out = new TreeSet<>(SOURCE_ORDER);
        out.addAll(sources);
        out.add(source);
        return out;
    }

    private static Set<VisitSource> sorted(Collection<VisitSource> sources) {
        Objects.requireNonNull(sources, "sources");
        TreeSet<VisitSource> out = new TreeSet<>(SOURCE_ORDER);
        for (VisitSource s : sources) {
            out.add(Objects.requireNonNull(s, "source"));
        }
        return Collections.unmodifiableSortedSet(out);
    }

    private static Instant earliest(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static Instant latest(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    // 손상된 저장 파일의 큰 횟수를 합쳐도 음수로 넘치지 않게 한다.
    private static int addCapped(int a, int b) {
        return (int) Math.min((long) a + b, Integer.MAX_VALUE);
    }
}
