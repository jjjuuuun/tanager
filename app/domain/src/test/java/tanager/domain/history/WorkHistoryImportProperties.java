package tanager.domain.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class WorkHistoryImportProperties {
    private static final ShellEnvironment U = new ShellEnvironment.Wsl("U");
    private static final ShellEnvironment V = new ShellEnvironment.Wsl("V");
    private static final ShellEnvironment WIN = new ShellEnvironment.Windows();
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final PathPattern TMP = PathPattern.of("/tmp/**").value().orElseThrow();

    private static Instant t(int hour) {
        return T0.plusSeconds(3600L * hour);
    }

    private static WorkspacePath path(ShellEnvironment env, String raw) {
        return WorkspacePath.of(env, raw).value().orElseThrow();
    }

    private static Arbitrary<WorkspacePath> paths() {
        Arbitrary<String> name = Arbitraries.of("a", "b", "c", "tmp", "x");
        Arbitrary<WorkspacePath> wsl = Combinators.combine(Arbitraries.of(U, V), name, name)
                .as((env, first, second) -> path(env, "/" + first + "/" + second));
        Arbitrary<WorkspacePath> win =
                Combinators.combine(Arbitraries.of("C:\\", "c:\\"), name).as((root, n) -> path(WIN, root + n));
        return Arbitraries.oneOf(wsl, win);
    }

    private static Arbitrary<VisitSource> sources() {
        return Arbitraries.of(VisitSource.SHELL, VisitSource.CLAUDE_CODE, new VisitSource("future-tool"));
    }

    private static Arbitrary<VisitedDirectory> entries() {
        Arbitrary<Integer> hours = Arbitraries.integers().between(0, 30);
        Arbitrary<Integer> counts = Arbitraries.integers().between(1, 50);
        Arbitrary<Set<VisitSource>> sourceSets = sources().set().ofMinSize(1);
        Arbitrary<Optional<Alias>> aliases = Arbitraries.of(Optional.empty(), Optional.of(new Alias("별칭")));
        Arbitrary<Integer> flags = Arbitraries.integers().between(0, 2);
        return Combinators.combine(paths(), hours, hours, counts, flags, aliases, sourceSets)
                .as((p, h1, h2, count, flag, alias, src) -> new VisitedDirectory(
                        p, t(Math.min(h1, h2)), t(Math.max(h1, h2)), count, flag == 1, alias, flag == 2, src));
    }

    private static Arbitrary<ImportedVisit> visits() {
        Arbitrary<Integer> hours = Arbitraries.integers().between(0, 40);
        return Combinators.combine(paths(), hours, hours)
                .as((p, h1, h2) -> new ImportedVisit(p, t(Math.min(h1, h2)), t(Math.max(h1, h2))));
    }

    @Provide
    Arbitrary<WorkHistory> histories() {
        Arbitrary<HistoryPolicy> policies = Combinators.combine(
                        Arbitraries.integers().between(1, 8), Arbitraries.of(List.<PathPattern>of(), List.of(TMP)))
                .as(HistoryPolicy::new);
        Arbitrary<Map<ImportOrigin, Instant>> watermarks = Arbitraries.integers()
                .between(-1, 40)
                .map(h -> h < 0
                        ? Map.<ImportOrigin, Instant>of()
                        : Map.of(new ImportOrigin(VisitSource.CLAUDE_CODE, U), t(h)));
        return Combinators.combine(entries().list().ofMaxSize(10), policies, watermarks)
                .as(WorkHistory::restore);
    }

    @Provide
    Arbitrary<ImportBatch> batches() {
        return Combinators.combine(
                        sources(),
                        Arbitraries.of(U, V, WIN),
                        Arbitraries.integers().between(0, 40),
                        visits().list().ofMaxSize(10))
                .as((source, env, scanned, visits) -> new ImportBatch(source, env, t(scanned), visits));
    }

    @Property
    @Label("같은 배치를 두 번 적용한 결과는 한 번 적용한 결과와 같다")
    void idempotent(@ForAll("histories") WorkHistory h, @ForAll("batches") ImportBatch batch) {
        // when
        WorkHistory once = h.importVisits(batch);

        // then
        assertThat(once.importVisits(batch)).isEqualTo(once);
    }

    @Property
    @Label("가져오기는 고정 항목 수와 남은 기존 항목의 방문 횟수 · 고정 · 별칭 · 숨김을 바꾸지 않는다")
    void keepsUserMarksAndCounts(@ForAll("histories") WorkHistory h, @ForAll("batches") ImportBatch batch) {
        // when
        WorkHistory next = h.importVisits(batch);

        // then
        assertThat(pinnedCount(next)).isEqualTo(pinnedCount(h));
        for (VisitedDirectory before : h.entries().values()) {
            Optional<VisitedDirectory> after = next.find(before.path());
            if (after.isPresent()) {
                assertThat(after.get().visitCount()).isEqualTo(before.visitCount());
                assertThat(after.get().pinned()).isEqualTo(before.pinned());
                assertThat(after.get().alias()).isEqualTo(before.alias());
                assertThat(after.get().hidden()).isEqualTo(before.hidden());
                assertThat(after.get().sources()).containsAll(before.sources());
            }
        }
    }

    @Property
    @Label("가져온 뒤 항목 수는 max(한도, 고정 수) 이하이고 제외 경로 · 다른 환경 경로는 새로 생기지 않는다")
    void boundedAndFiltered(@ForAll("histories") WorkHistory h, @ForAll("batches") ImportBatch batch) {
        // when
        WorkHistory next = h.importVisits(batch);

        // then
        assertThat((long) next.entries().size())
                .isLessThanOrEqualTo(Math.max(
                        Math.max(next.policy().maxEntries(), pinnedCount(next)),
                        h.entries().size()));
        for (VisitedDirectory v : next.entries().values()) {
            if (h.find(v.path()).isEmpty()) {
                assertThat(v.path().env()).isEqualTo(batch.env());
                assertThat(next.policy().excludes(v.path())).isFalse();
                assertThat(v.sources()).containsExactly(batch.source());
            }
        }
        assertThat(next.importedUntil(batch.origin())).isPresent();
    }

    private static long pinnedCount(WorkHistory h) {
        return h.entries().values().stream().filter(VisitedDirectory::pinned).count();
    }
}
