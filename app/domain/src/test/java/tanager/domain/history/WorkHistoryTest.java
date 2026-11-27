package tanager.domain.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.domain.shared.Result;

class WorkHistoryTest {

    private static final ShellEnvironment WIN = new ShellEnvironment.Windows();
    private static final ShellEnvironment WSL = new ShellEnvironment.Wsl("Ubuntu");
    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-01-02T00:00:00Z");
    private static final Instant T3 = Instant.parse("2026-01-03T00:00:00Z");
    private static final Instant T4 = Instant.parse("2026-01-04T00:00:00Z");
    private static final Instant T5 = Instant.parse("2026-01-05T00:00:00Z");

    private static WorkspacePath path(ShellEnvironment env, String raw) {
        return WorkspacePath.of(env, raw).value().orElseThrow();
    }

    private static WorkHistory empty() {
        return WorkHistory.empty(HistoryPolicy.DEFAULT);
    }

    private static WorkHistory ok(Result<WorkHistory, HistoryError> r) {
        assertThat(r.isOk()).as("성공해야 한다: %s", r.error()).isTrue();
        return r.value().orElseThrow();
    }

    private static VisitedDirectory entry(
            WorkspacePath p, Instant first, Instant last, int count, boolean pinned, Optional<Alias> alias) {
        return new VisitedDirectory(p, first, last, count, pinned, alias, false);
    }

    // ---- recordVisit ----

    @Test
    @DisplayName("빈 이력에 첫 방문을 기록하면 count 1, first = last, 고정 · 숨김 아님")
    void firstVisit() {
        // given
        WorkspacePath p = path(WSL, "/a");
        // when
        WorkHistory h = empty().recordVisit(p, T1);
        // then
        assertThat(h.entries()).hasSize(1);
        VisitedDirectory v = h.find(p).orElseThrow();
        assertThat(v.visitCount()).isEqualTo(1);
        assertThat(v.firstVisited()).isEqualTo(T1);
        assertThat(v.lastVisited()).isEqualTo(T1);
        assertThat(v.pinned()).isFalse();
        assertThat(v.hidden()).isFalse();
        assertThat(v.alias()).isEmpty();
    }

    @Test
    @DisplayName("Windows 에서 대소문자만 다른 경로 방문은 같은 항목이고 표기는 처음 것이다")
    void windowsCaseInsensitiveRevisit() {
        // given
        WorkHistory h = empty().recordVisit(path(WIN, "C:\\Work"), T1);
        // when
        WorkHistory next = h.recordVisit(path(WIN, "c:\\WORK"), T2);
        // then
        assertThat(next.entries()).hasSize(1);
        VisitedDirectory v = next.find(path(WIN, "C:\\Work")).orElseThrow();
        assertThat(v.visitCount()).isEqualTo(2);
        assertThat(v.lastVisited()).isEqualTo(T2);
        assertThat(v.path().path()).isEqualTo("C:\\Work");
    }

    @Test
    @DisplayName("WSL 에서 대소문자만 다른 경로는 두 항목이다")
    void wslCaseSensitive() {
        // given / when
        WorkHistory h = empty().recordVisit(path(WSL, "/a"), T1).recordVisit(path(WSL, "/A"), T2);
        // then
        assertThat(h.entries()).hasSize(2);
    }

    @Test
    @DisplayName("시계가 뒤로 가도 count 는 늘고 last 는 유지되며 first <= last 다")
    void clockGoingBackwards() {
        // given
        WorkspacePath p = path(WSL, "/a");
        WorkHistory h = empty().recordVisit(p, T2);
        // when
        VisitedDirectory v = h.recordVisit(p, T1).find(p).orElseThrow();
        // then
        assertThat(v.visitCount()).isEqualTo(2);
        assertThat(v.lastVisited()).isEqualTo(T2);
        assertThat(v.firstVisited()).isBeforeOrEqualTo(v.lastVisited());
    }

    @Test
    @DisplayName("recordVisit 은 원래 이력을 바꾸지 않는다")
    void recordVisitDoesNotMutate() {
        // given
        WorkHistory h = empty();
        // when
        h.recordVisit(path(WSL, "/a"), T1);
        // then
        assertThat(h.entries()).isEmpty();
    }

    @Test
    @DisplayName("한도를 넘으면 고정 아닌 항목 중 가장 오래 전에 방문한 것부터 지운다")
    void evictsOldestNonPinned() {
        // given
        WorkspacePath pinned = path(WSL, "/pinned");
        WorkspacePath old = path(WSL, "/old");
        WorkspacePath mid = path(WSL, "/mid");
        WorkHistory h = WorkHistory.empty(new HistoryPolicy(3))
                .recordVisit(pinned, T1)
                .recordVisit(old, T2)
                .recordVisit(mid, T3);
        h = ok(h.pin(pinned, true));
        // when
        WorkHistory next = h.recordVisit(path(WSL, "/n1"), T4).recordVisit(path(WSL, "/n2"), T5);
        // then
        assertThat(next.entries()).hasSize(3);
        assertThat(next.find(pinned)).isPresent();
        assertThat(next.find(old)).isEmpty();
        assertThat(next.find(mid)).isEmpty();
        assertThat(next.find(path(WSL, "/n1"))).isPresent();
        assertThat(next.find(path(WSL, "/n2"))).isPresent();
    }

    @Test
    @DisplayName("고정 항목만 한도보다 많아도 고정은 지우지 않고 새 항목은 남긴다")
    void neverEvictsPinned() {
        // given
        WorkHistory h = WorkHistory.empty(new HistoryPolicy(2));
        for (String n : List.of("/a", "/b", "/c")) {
            WorkspacePath p = path(WSL, n);
            h = ok(h.recordVisit(p, T1).pin(p, true));
        }
        // when
        WorkHistory next = h.recordVisit(path(WSL, "/new"), T2);
        // then
        assertThat(next.find(path(WSL, "/a"))).isPresent();
        assertThat(next.find(path(WSL, "/b"))).isPresent();
        assertThat(next.find(path(WSL, "/c"))).isPresent();
        assertThat(next.find(path(WSL, "/new"))).isPresent();
    }

    @Test
    @DisplayName("숨긴 항목을 다시 방문하면 숨김은 유지되고 count · last 는 갱신된다")
    void hiddenStaysHiddenOnRevisit() {
        // given
        WorkspacePath p = path(WSL, "/a");
        WorkHistory h = ok(empty().recordVisit(p, T1).hide(p, true));
        // when
        VisitedDirectory v = h.recordVisit(p, T2).find(p).orElseThrow();
        // then
        assertThat(v.hidden()).isTrue();
        assertThat(v.visitCount()).isEqualTo(2);
        assertThat(v.lastVisited()).isEqualTo(T2);
    }

    // ---- pin / hide ----

    @Test
    @DisplayName("숨긴 항목을 고정하면 고정되고 숨김은 풀린다")
    void pinClearsHidden() {
        // given
        WorkspacePath p = path(WSL, "/a");
        WorkHistory h = ok(empty().recordVisit(p, T1).hide(p, true));
        // when
        VisitedDirectory v = ok(h.pin(p, true)).find(p).orElseThrow();
        // then
        assertThat(v.pinned()).isTrue();
        assertThat(v.hidden()).isFalse();
    }

    @Test
    @DisplayName("고정 항목을 숨기면 숨겨지고 고정은 풀린다")
    void hideClearsPinned() {
        // given
        WorkspacePath p = path(WSL, "/a");
        WorkHistory h = ok(empty().recordVisit(p, T1).pin(p, true));
        // when
        VisitedDirectory v = ok(h.hide(p, true)).find(p).orElseThrow();
        // then
        assertThat(v.hidden()).isTrue();
        assertThat(v.pinned()).isFalse();
    }

    @Test
    @DisplayName("pin(false) 와 hide(false) 는 해당 표시만 푼다")
    void unpinAndUnhide() {
        // given
        WorkspacePath p = path(WSL, "/a");
        WorkHistory pinned = ok(empty().recordVisit(p, T1).pin(p, true));
        WorkHistory hidden = ok(empty().recordVisit(p, T1).hide(p, true));
        // when / then
        assertThat(ok(pinned.pin(p, false)).find(p).orElseThrow().pinned()).isFalse();
        assertThat(ok(hidden.hide(p, false)).find(p).orElseThrow().hidden()).isFalse();
    }

    // ---- alias ----

    @Test
    @DisplayName("별칭은 앞뒤 공백을 벗겨 저장한다")
    void aliasIsStripped() {
        // given
        WorkspacePath p = path(WSL, "/a");
        WorkHistory h = empty().recordVisit(p, T1);
        // when
        VisitedDirectory v = ok(h.alias(p, Optional.of("  Tanager  "))).find(p).orElseThrow();
        // then
        assertThat(v.alias().orElseThrow().value()).isEqualTo("Tanager");
    }

    @Test
    @DisplayName("빈 값 · 공백 · empty 별칭은 기존 별칭을 지운다")
    void blankAliasClears() {
        // given
        WorkspacePath p = path(WSL, "/a");
        WorkHistory h = ok(empty().recordVisit(p, T1).alias(p, Optional.of("x")));
        // when / then
        assertThat(ok(h.alias(p, Optional.of(""))).find(p).orElseThrow().alias())
                .isEmpty();
        assertThat(ok(h.alias(p, Optional.of("   "))).find(p).orElseThrow().alias())
                .isEmpty();
        assertThat(ok(h.alias(p, Optional.empty())).find(p).orElseThrow().alias())
                .isEmpty();
    }

    @Test
    @DisplayName("65자 · 제어 문자 별칭은 InvalidAlias 이고 이력은 그대로다")
    void invalidAlias() {
        // given
        WorkspacePath p = path(WSL, "/a");
        WorkHistory h = empty().recordVisit(p, T1);
        // when
        var tooLong = h.alias(p, Optional.of("a".repeat(65)));
        var control = h.alias(p, Optional.of("a\nb"));
        // then
        assertThat(tooLong.error().orElseThrow()).isInstanceOf(HistoryError.InvalidAlias.class);
        assertThat(control.error().orElseThrow()).isInstanceOf(HistoryError.InvalidAlias.class);
        assertThat(h.find(p).orElseThrow().alias()).isEmpty();
    }

    // ---- NotFound ----

    @Test
    @DisplayName("없는 경로에 pin · alias · hide · forget 하면 NotFound 이고 이력은 그대로다")
    void notFound() {
        // given
        WorkHistory h = empty().recordVisit(path(WSL, "/a"), T1);
        WorkspacePath missing = path(WSL, "/missing");
        // when / then
        List<Result<WorkHistory, HistoryError>> results = List.of(
                h.pin(missing, true), h.alias(missing, Optional.of("x")), h.hide(missing, true), h.forget(missing));
        for (var r : results) {
            assertThat(r.error().orElseThrow()).isEqualTo(new HistoryError.NotFound(missing));
        }
        assertThat(h.entries()).hasSize(1);
    }

    // ---- forget ----

    @Test
    @DisplayName("forget 하면 항목이 사라지고 다시 방문하면 count 1 로 새로 시작한다")
    void forgetThenRevisit() {
        // given
        WorkspacePath p = path(WSL, "/a");
        WorkHistory h = empty().recordVisit(p, T1).recordVisit(p, T2);
        // when
        WorkHistory forgotten = ok(h.forget(p));
        VisitedDirectory again = forgotten.recordVisit(p, T3).find(p).orElseThrow();
        // then
        assertThat(forgotten.find(p)).isEmpty();
        assertThat(again.visitCount()).isEqualTo(1);
        assertThat(again.firstVisited()).isEqualTo(T3);
    }

    // ---- restore ----

    @Test
    @DisplayName("restore 도 한도를 적용해 고정 아닌 가장 오래된 항목부터 지운다")
    void restoreAppliesLimit() {
        // given: 한도 2, 일반 3 + 고정 1
        List<VisitedDirectory> saved = List.of(
                entry(path(WSL, "/old"), T1, T1, 1, false, Optional.empty()),
                entry(path(WSL, "/mid"), T2, T2, 1, false, Optional.empty()),
                entry(path(WSL, "/new"), T3, T3, 1, false, Optional.empty()),
                entry(path(WSL, "/pin"), T1, T1, 1, true, Optional.empty()));
        // when
        WorkHistory h = WorkHistory.restore(saved, new HistoryPolicy(2));
        // then
        assertThat(h.entries().keySet()).containsExactlyInAnyOrder(path(WSL, "/new"), path(WSL, "/pin"));
    }

    @Test
    @DisplayName("최근 방문이 같은 항목끼리는 먼저 방문한 쪽, 그다음 경로 순으로 지운다 — 결과가 순회 순서에 달리지 않는다")
    void evictionTieBreakIsDeterministic() {
        // given: 한도 1, lastVisited 가 같은 세 항목
        List<VisitedDirectory> saved = List.of(
                entry(path(WSL, "/b"), T1, T3, 1, false, Optional.empty()),
                entry(path(WSL, "/a"), T1, T3, 1, false, Optional.empty()),
                entry(path(WSL, "/c"), T2, T3, 1, false, Optional.empty()));
        // when
        WorkHistory h = WorkHistory.restore(saved, new HistoryPolicy(1));
        // then: first 가 늦은 /c 가 남는다
        assertThat(h.entries().keySet()).containsExactly(path(WSL, "/c"));
    }

    @Test
    @DisplayName("큰 저장 파일(2만 항목)을 한도 2000 으로 불러와도 가장 최근 2000 개가 남는다")
    void restoreLargeFileKeepsMostRecent() {
        // given
        List<VisitedDirectory> saved = new java.util.ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            Instant t = T1.plusSeconds(i);
            saved.add(entry(path(WSL, "/d" + i), t, t, 1, false, Optional.empty()));
        }
        // when
        WorkHistory h = WorkHistory.restore(saved, HistoryPolicy.DEFAULT);
        // then
        assertThat(h.entries()).hasSize(2000);
        assertThat(h.find(path(WSL, "/d19999"))).isPresent();
        assertThat(h.find(path(WSL, "/d18000"))).isPresent();
        assertThat(h.find(path(WSL, "/d17999"))).isEmpty();
    }

    @Test
    @DisplayName("restore 는 숨김을 OR 로 합친다(둘 다 고정이 아닐 때)")
    void restoreMergesHiddenWithOr() {
        // given
        VisitedDirectory hidden = new VisitedDirectory(path(WIN, "C:\\Tmp"), T1, T1, 1, false, Optional.empty(), true);
        VisitedDirectory plain = entry(path(WIN, "c:\\tmp"), T2, T2, 1, false, Optional.empty());
        // when
        WorkHistory h = WorkHistory.restore(List.of(plain, hidden), HistoryPolicy.DEFAULT);
        // then
        assertThat(h.find(path(WIN, "C:\\Tmp")).orElseThrow().hidden()).isTrue();
    }

    @Test
    @DisplayName("방문 횟수는 int 최대값에서 멈추고 음수로 넘치지 않는다")
    void visitCountSaturates() {
        // given
        WorkspacePath p = path(WSL, "/a");
        VisitedDirectory big = entry(p, T1, T1, Integer.MAX_VALUE, false, Optional.empty());
        // when
        WorkHistory h = WorkHistory.restore(
                        List.of(big, entry(p, T2, T2, 5, false, Optional.empty())), HistoryPolicy.DEFAULT)
                .recordVisit(p, T3);
        // then
        assertThat(h.find(p).orElseThrow().visitCount()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("키와 항목 경로가 다른 맵으로는 WorkHistory 를 만들 수 없다")
    void constructorRejectsMismatchedKey() {
        // given
        VisitedDirectory v = entry(path(WSL, "/a"), T1, T1, 1, false, Optional.empty());
        // when / then
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new WorkHistory(java.util.Map.of(path(WSL, "/b"), v), HistoryPolicy.DEFAULT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("restore 는 대소문자만 다른 Windows 항목을 합친다(count 합, first 최소, last 최대, 고정 OR, 먼저 있는 별칭)")
    void restoreMergesDuplicates() {
        // given
        Alias alias = Alias.of("Work").value().orElseThrow();
        VisitedDirectory a = entry(path(WIN, "C:\\Work"), T2, T3, 3, false, Optional.empty());
        VisitedDirectory b = entry(path(WIN, "c:\\work"), T1, T4, 2, true, Optional.of(alias));
        // when
        WorkHistory h = WorkHistory.restore(List.of(a, b), HistoryPolicy.DEFAULT);
        // then
        assertThat(h.entries()).hasSize(1);
        VisitedDirectory v = h.find(path(WIN, "C:\\Work")).orElseThrow();
        assertThat(v.visitCount()).isEqualTo(5);
        assertThat(v.firstVisited()).isEqualTo(T1);
        assertThat(v.lastVisited()).isEqualTo(T4);
        assertThat(v.pinned()).isTrue();
        assertThat(v.alias()).contains(alias);
        assertThat(v.path().path()).isEqualTo("C:\\Work");
    }

    @Test
    @DisplayName("두 항목에 별칭이 모두 있으면 먼저 나온 별칭을 쓴다")
    void restoreFirstAliasWins() {
        // given
        Alias first = Alias.of("one").value().orElseThrow();
        Alias second = Alias.of("two").value().orElseThrow();
        VisitedDirectory a = entry(path(WIN, "C:\\Work"), T1, T1, 1, false, Optional.of(first));
        VisitedDirectory b = entry(path(WIN, "c:\\work"), T1, T1, 1, false, Optional.of(second));
        // when
        WorkHistory h = WorkHistory.restore(List.of(a, b), HistoryPolicy.DEFAULT);
        // then
        assertThat(h.find(a.path()).orElseThrow().alias()).contains(first);
    }

    @Test
    @DisplayName("고정과 숨김이 합쳐지면 고정이 이긴다(불변식 유지)")
    void restorePinnedBeatsHidden() {
        // given
        VisitedDirectory pinned = entry(path(WIN, "C:\\Work"), T1, T1, 1, true, Optional.empty());
        VisitedDirectory hidden = new VisitedDirectory(path(WIN, "c:\\work"), T1, T1, 1, false, Optional.empty(), true);
        // when
        VisitedDirectory v = WorkHistory.restore(List.of(pinned, hidden), HistoryPolicy.DEFAULT)
                .find(pinned.path())
                .orElseThrow();
        // then
        assertThat(v.pinned()).isTrue();
        assertThat(v.hidden()).isFalse();
    }

    // ---- 속성 ----

    @Property
    @Label("임의의 방문 열 뒤에도 항목 수는 max(한도, 고정 수 + 방금 방문한 1) 이하이고 first <= last, count >= 1 이다")
    void invariantsHoldAfterRandomVisits(
            @ForAll @IntRange(min = 1, max = 5) int max,
            @ForAll List<@IntRange(min = 0, max = 8) Integer> visits,
            @ForAll List<@IntRange(min = 0, max = 8) Integer> pins) {
        // given
        WorkHistory h = WorkHistory.empty(new HistoryPolicy(max));
        Instant t = T1;
        // when
        for (int n : visits) {
            WorkspacePath p = path(WSL, "/d" + n);
            h = h.recordVisit(p, t);
            t = t.plusSeconds(1);
            if (pins.contains(n)) {
                h = h.pin(p, true).value().orElse(h);
            }
        }
        // then
        long pinnedCount =
                h.entries().values().stream().filter(VisitedDirectory::pinned).count();
        assertThat((long) h.entries().size()).isLessThanOrEqualTo(Math.max(max, pinnedCount + 1));
        for (VisitedDirectory v : h.entries().values()) {
            assertThat(v.visitCount()).isGreaterThanOrEqualTo(1);
            assertThat(v.firstVisited()).isBeforeOrEqualTo(v.lastVisited());
        }
    }
}
