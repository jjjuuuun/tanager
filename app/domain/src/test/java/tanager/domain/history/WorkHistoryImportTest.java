package tanager.domain.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorkHistoryImportTest {
    private static final ShellEnvironment U = new ShellEnvironment.Wsl("U");
    private static final ShellEnvironment V = new ShellEnvironment.Wsl("V");
    private static final ShellEnvironment WIN = new ShellEnvironment.Windows();
    private static final ImportOrigin CLAUDE_U = new ImportOrigin(VisitSource.CLAUDE_CODE, U);

    private static Instant t(int n) {
        return Instant.parse("2026-01-01T00:00:00Z").plusSeconds(3600L * n);
    }

    private static WorkspacePath path(ShellEnvironment env, String raw) {
        return WorkspacePath.of(env, raw).value().orElseThrow();
    }

    private static final WorkspacePath A = path(U, "/home/me/a");

    private static ImportBatch claudeU(Instant scannedAt, ImportedVisit... visits) {
        return new ImportBatch(VisitSource.CLAUDE_CODE, U, scannedAt, List.of(visits));
    }

    private static ImportedVisit visit(WorkspacePath p, Instant first, Instant last) {
        return new ImportedVisit(p, first, last);
    }

    private static <T> T ok(tanager.domain.shared.Result<T, HistoryError> r) {
        return r.value().orElseThrow();
    }

    @Test
    @DisplayName("빈 이력에 가져오면 항목을 만들고 출처의 워터마크를 scannedAt 으로 둔다")
    void importIntoEmpty() {
        // given
        WorkHistory h = WorkHistory.empty(HistoryPolicy.DEFAULT);

        // when
        WorkHistory next = h.importVisits(claudeU(t(10), visit(A, t(1), t(5))));

        // then
        VisitedDirectory a = next.find(A).orElseThrow();
        assertThat(a.firstVisited()).isEqualTo(t(1));
        assertThat(a.lastVisited()).isEqualTo(t(5));
        assertThat(a.visitCount()).isEqualTo(1);
        assertThat(a.sources()).containsExactly(VisitSource.CLAUDE_CODE);
        assertThat(a.usedBy(VisitSource.CLAUDE_CODE)).isTrue();
        assertThat(a.usedBy(VisitSource.SHELL)).isFalse();
        assertThat(next.importedUntil(CLAUDE_U)).contains(t(10));
    }

    @Test
    @DisplayName("셸로 방문한 항목에 가져오면 출처만 더하고 횟수 · 고정 · 별칭은 그대로, 시각은 넓힌다")
    void importIntoExisting() {
        // given
        WorkHistory h = WorkHistory.empty(HistoryPolicy.DEFAULT);
        for (int i = 0; i < 7; i++) {
            h = h.recordVisit(A, t(2 + i % 2));
        }
        h = ok(h.pin(A, true));
        h = ok(h.alias(A, Optional.of("A")));

        // when
        WorkHistory next = h.importVisits(claudeU(t(10), visit(A, t(1), t(5))));

        // then
        VisitedDirectory a = next.find(A).orElseThrow();
        assertThat(a.sources()).containsExactly(VisitSource.CLAUDE_CODE, VisitSource.SHELL);
        assertThat(a.visitCount()).isEqualTo(7);
        assertThat(a.pinned()).isTrue();
        assertThat(a.alias()).contains(new Alias("A"));
        assertThat(a.firstVisited()).isEqualTo(t(1));
        assertThat(a.lastVisited()).isEqualTo(t(5));
    }

    @Test
    @DisplayName("셸 방문이 가져온 기간보다 늦으면 최근 방문은 셸 쪽 시각이 남는다")
    void lastIsMax() {
        // given
        WorkHistory h = WorkHistory.empty(HistoryPolicy.DEFAULT).recordVisit(A, t(8));

        // when
        WorkHistory next = h.importVisits(claudeU(t(10), visit(A, t(1), t(5))));

        // then
        VisitedDirectory a = next.find(A).orElseThrow();
        assertThat(a.firstVisited()).isEqualTo(t(1));
        assertThat(a.lastVisited()).isEqualTo(t(8));
    }

    @Test
    @DisplayName("숨긴 항목에 가져와도 숨김은 그대로다")
    void hiddenStays() {
        // given
        WorkHistory h =
                ok(WorkHistory.empty(HistoryPolicy.DEFAULT).recordVisit(A, t(2)).hide(A, true));

        // when
        WorkHistory next = h.importVisits(claudeU(t(10), visit(A, t(1), t(5))));

        // then
        assertThat(next.find(A).orElseThrow().hidden()).isTrue();
    }

    @Test
    @DisplayName("같은 배치를 두 번 적용해도 결과가 같다")
    void idempotent() {
        // given
        WorkHistory h = WorkHistory.empty(HistoryPolicy.DEFAULT).recordVisit(path(U, "/b"), t(3));
        ImportBatch batch = claudeU(t(10), visit(A, t(1), t(5)), visit(path(U, "/b"), t(2), t(4)));

        // when
        WorkHistory once = h.importVisits(batch);
        WorkHistory twice = once.importVisits(batch);

        // then
        assertThat(twice).isEqualTo(once);
    }

    @Test
    @DisplayName("배치 안의 같은 경로는 합쳐서 본다 — 두 번 적용해도 같다")
    void sameKeyInBatchMerged() {
        // given
        WorkHistory h = WorkHistory.empty(HistoryPolicy.DEFAULT)
                .importVisits(claudeU(t(10), visit(path(U, "/old"), t(1), t(2))));
        ImportBatch batch = claudeU(t(20), visit(A, t(1), t(3)), visit(A, t(11), t(12)));

        // when
        WorkHistory once = h.importVisits(batch);

        // then
        VisitedDirectory a = once.find(A).orElseThrow();
        assertThat(a.firstVisited()).isEqualTo(t(1));
        assertThat(a.lastVisited()).isEqualTo(t(12));
        assertThat(a.visitCount()).isEqualTo(1);
        assertThat(once.importVisits(batch)).isEqualTo(once);
    }

    @Test
    @DisplayName("가져온 항목을 지우면 같은 기록을 다시 가져와도 돌아오지 않는다")
    void forgottenStaysForgotten() {
        // given
        ImportBatch batch = claudeU(t(10), visit(A, t(1), t(5)));
        WorkHistory h =
                ok(WorkHistory.empty(HistoryPolicy.DEFAULT).importVisits(batch).forget(A));

        // when
        WorkHistory next = h.importVisits(claudeU(t(11), visit(A, t(1), t(5))));

        // then
        assertThat(next.find(A)).isEmpty();
        assertThat(next.importedUntil(CLAUDE_U)).contains(t(11));
    }

    @Test
    @DisplayName("지운 뒤 그 경로에서 새 대화가 생기면 다시 가져온다")
    void newConversationAfterForgetReturns() {
        // given
        WorkHistory h = ok(WorkHistory.empty(HistoryPolicy.DEFAULT)
                .importVisits(claudeU(t(10), visit(A, t(1), t(5))))
                .forget(A));

        // when
        WorkHistory next = h.importVisits(claudeU(t(13), visit(A, t(1), t(12))));

        // then
        VisitedDirectory a = next.find(A).orElseThrow();
        assertThat(a.firstVisited()).isEqualTo(t(1));
        assertThat(a.lastVisited()).isEqualTo(t(12));
        assertThat(a.visitCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("워터마크는 (출처, 환경)마다 따로다 — 다른 배포판의 가져오기는 영향이 없다")
    void watermarkPerOrigin() {
        // given
        WorkspacePath b = path(V, "/home/me/b");
        WorkHistory h = WorkHistory.empty(HistoryPolicy.DEFAULT).importVisits(claudeU(t(10), visit(A, t(1), t(5))));

        // when
        WorkHistory next =
                h.importVisits(new ImportBatch(VisitSource.CLAUDE_CODE, V, t(11), List.of(visit(b, t(1), t(2)))));

        // then
        assertThat(next.find(b)).isPresent();
        assertThat(next.importedUntil(CLAUDE_U)).contains(t(10));
        assertThat(next.importedUntil(new ImportOrigin(VisitSource.CLAUDE_CODE, V)))
                .contains(t(11));
        assertThat(next.importedUntil(new ImportOrigin(VisitSource.SHELL, U))).isEmpty();
    }

    @Test
    @DisplayName("시계가 뒤로 가도 워터마크는 줄지 않는다")
    void watermarkNeverDecreases() {
        // given
        WorkHistory h = WorkHistory.empty(HistoryPolicy.DEFAULT).importVisits(claudeU(t(10)));

        // when
        WorkHistory next = h.importVisits(claudeU(t(8)));

        // then
        assertThat(next.importedUntil(CLAUDE_U)).contains(t(10));
    }

    @Test
    @DisplayName("scannedAt 보다 늦은 방문이 있으면 워터마크는 그 시각까지 간다 — 지운 경로가 다음 시작에 돌아오지 않게")
    void watermarkCoversFutureVisits() {
        // given
        WorkHistory h = WorkHistory.empty(HistoryPolicy.DEFAULT);

        // when
        WorkHistory next = h.importVisits(claudeU(t(10), visit(A, t(1), t(15))));

        // then
        assertThat(next.importedUntil(CLAUDE_U)).contains(t(15));
        WorkHistory forgotten = ok(next.forget(A));
        assertThat(forgotten.importVisits(claudeU(t(11), visit(A, t(1), t(15)))).find(A))
                .isEmpty();
    }

    @Test
    @DisplayName("제외 규칙에 맞는 경로는 건너뛴다")
    void excludedSkipped() {
        // given
        HistoryPolicy policy = new HistoryPolicy(
                2000, List.of(PathPattern.of("/tmp/**").value().orElseThrow()));
        WorkHistory h = WorkHistory.empty(policy);

        // when
        WorkHistory next = h.importVisits(claudeU(t(10), visit(path(U, "/tmp/x"), t(1), t(5))));

        // then
        assertThat(next.entries()).isEmpty();
    }

    @Test
    @DisplayName("배치와 다른 환경의 경로는 건너뛴다")
    void otherEnvironmentSkipped() {
        // given
        WorkHistory h = WorkHistory.empty(HistoryPolicy.DEFAULT).recordVisit(path(WIN, "C:\\w"), t(1));

        // when
        WorkHistory next = h.importVisits(claudeU(
                t(10),
                visit(path(WIN, "C:\\w"), t(1), t(5)),
                visit(path(WIN, "C:\\x"), t(1), t(5)),
                visit(path(V, "/v"), t(1), t(5))));

        // then
        assertThat(next.entries().keySet()).containsExactly(path(WIN, "C:\\w"));
        assertThat(next.find(path(WIN, "C:\\w")).orElseThrow().sources()).containsExactly(VisitSource.SHELL);
    }

    @Test
    @DisplayName("한도를 넘으면 고정 안 된 오래된 항목부터 지운다")
    void evictsLikeRecordVisit() {
        // given
        WorkHistory h = WorkHistory.empty(new HistoryPolicy(2))
                .recordVisit(path(U, "/pinned"), t(0))
                .recordVisit(path(U, "/old"), t(1));
        h = ok(h.pin(path(U, "/pinned"), true));

        // when
        WorkHistory next = h.importVisits(claudeU(t(10), visit(A, t(2), t(5))));

        // then
        assertThat(next.entries().keySet()).containsExactlyInAnyOrder(path(U, "/pinned"), A);
    }

    @Test
    @DisplayName("기록 · 고정 · 지우기 · 정책 바꾸기는 워터마크를 그대로 둔다")
    void otherOperationsKeepWatermark() {
        // given
        WorkHistory h = WorkHistory.empty(HistoryPolicy.DEFAULT).importVisits(claudeU(t(10), visit(A, t(1), t(5))));

        // when
        List<WorkHistory> results = new ArrayList<>();
        results.add(h.recordVisit(path(U, "/b"), t(11)));
        results.add(ok(h.pin(A, true)));
        results.add(ok(h.hide(A, true)));
        results.add(ok(h.alias(A, Optional.of("x"))));
        results.add(ok(h.forget(A)));
        results.add(h.withPolicy(new HistoryPolicy(5)));

        // then
        for (WorkHistory r : results) {
            assertThat(r.importedUntil()).isEqualTo(Map.of(CLAUDE_U, t(10)));
        }
    }

    @Test
    @DisplayName("restore 는 워터마크를 받아 그대로 둔다")
    void restoreKeepsWatermark() {
        // given
        Map<ImportOrigin, Instant> until = Map.of(CLAUDE_U, t(10));

        // when
        WorkHistory h = WorkHistory.restore(List.of(), HistoryPolicy.DEFAULT, until);

        // then
        assertThat(h.importedUntil()).isEqualTo(until);
        assertThat(WorkHistory.restore(List.of(), HistoryPolicy.DEFAULT).importedUntil())
                .isEmpty();
    }

    @Test
    @DisplayName("recordVisit 은 출처에 shell 을 더한다")
    void recordVisitAddsShell() {
        // given
        WorkHistory h = WorkHistory.empty(HistoryPolicy.DEFAULT).importVisits(claudeU(t(10), visit(A, t(1), t(5))));

        // when
        WorkHistory next = h.recordVisit(A, t(11)).recordVisit(path(U, "/b"), t(12));

        // then
        assertThat(next.find(A).orElseThrow().sources()).containsExactly(VisitSource.CLAUDE_CODE, VisitSource.SHELL);
        assertThat(next.find(A).orElseThrow().visitCount()).isEqualTo(2);
        assertThat(next.find(path(U, "/b")).orElseThrow().sources()).containsExactly(VisitSource.SHELL);
    }

    @Test
    @DisplayName("restore 가 같은 키를 합치면 출처도 합친다")
    void restoreUnionsSources() {
        // given
        WorkspacePath upper = path(WIN, "C:\\Work");
        WorkspacePath lower = path(WIN, "c:\\work");
        VisitedDirectory first =
                new VisitedDirectory(upper, t(1), t(2), 1, false, Optional.empty(), false, Set.of(VisitSource.SHELL));
        VisitedDirectory later = new VisitedDirectory(
                lower, t(1), t(2), 1, false, Optional.empty(), false, Set.of(new VisitSource("x")));

        // when
        WorkHistory h = WorkHistory.restore(List.of(first, later), HistoryPolicy.DEFAULT);

        // then
        assertThat(h.find(upper).orElseThrow().sources()).containsExactly(VisitSource.SHELL, new VisitSource("x"));
    }

    @Test
    @DisplayName("가져온 방문은 처음이 마지막보다 늦을 수 없다")
    void importedVisitInvariant() {
        // when / then
        assertThatThrownBy(() -> new ImportedVisit(A, t(2), t(1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("배치의 방문 목록은 바깥에서 바꿀 수 없고 origin 은 (출처, 환경)이다")
    void batchCopiesVisits() {
        // given
        List<ImportedVisit> visits = new ArrayList<>(List.of(visit(A, t(1), t(2))));
        ImportBatch batch = new ImportBatch(VisitSource.CLAUDE_CODE, U, t(10), visits);

        // when
        visits.clear();

        // then
        assertThat(batch.visits()).hasSize(1);
        assertThat(batch.origin()).isEqualTo(CLAUDE_U);
    }
}
