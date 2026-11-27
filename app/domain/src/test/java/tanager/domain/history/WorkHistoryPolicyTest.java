package tanager.domain.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorkHistoryPolicyTest {
    private static final ShellEnvironment UBUNTU = new ShellEnvironment.Wsl("Ubuntu");
    private static final ShellEnvironment WIN = new ShellEnvironment.Windows();
    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00Z");

    private static WorkspacePath wsl(String raw) {
        return WorkspacePath.of(UBUNTU, raw).value().orElseThrow();
    }

    private static PathPattern glob(String raw) {
        return PathPattern.of(raw).value().orElseThrow();
    }

    private static Instant at(int day) {
        return T1.plusSeconds(86_400L * day);
    }

    private static WorkHistory pinned(WorkHistory h, WorkspacePath p) {
        return h.pin(p, true).value().orElseThrow();
    }

    @Test
    @DisplayName("maxEntries 가 1 보다 작으면 정책을 만들 수 없다")
    void invariant() {
        // when / then
        assertThatThrownBy(() -> new HistoryPolicy(0, List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("정책은 제외 규칙 중 하나라도 맞으면 그 경로를 제외한다")
    void excludes() {
        // given
        HistoryPolicy policy = new HistoryPolicy(10, List.of(glob("/tmp/**"), glob("**/node_modules/**")));
        // when / then
        assertThat(policy.excludes(wsl("/tmp/x"))).isTrue();
        assertThat(policy.excludes(
                        WorkspacePath.of(WIN, "C:\\p\\node_modules").value().orElseThrow()))
                .isTrue();
        assertThat(policy.excludes(wsl("/home/me"))).isFalse();
        assertThat(new HistoryPolicy(10).excludes(wsl("/tmp/x"))).isFalse();
    }

    @Test
    @DisplayName("제외 경로 방문은 기록하지 않고 같은 인스턴스를 돌려준다")
    void excludedVisitIgnored() {
        // given
        WorkHistory h = WorkHistory.empty(new HistoryPolicy(10, List.of(glob("/tmp/**"))))
                .recordVisit(wsl("/a"), T1);
        // when
        WorkHistory next = h.recordVisit(wsl("/tmp/x"), at(1));
        // then
        assertThat(next).isSameAs(h);
        assertThat(next.find(wsl("/tmp/x"))).isEmpty();
    }

    @Test
    @DisplayName("제외 규칙을 더하면 고정 안 된 항목은 지우고 고정 항목은 남긴다")
    void withPolicyKeepsPinned() {
        // given
        WorkHistory h = WorkHistory.empty(HistoryPolicy.DEFAULT)
                .recordVisit(wsl("/tmp/keep"), T1)
                .recordVisit(wsl("/tmp/old"), at(1))
                .recordVisit(wsl("/home/me"), at(2));
        h = pinned(h, wsl("/tmp/keep"));
        // when
        WorkHistory next = h.withPolicy(new HistoryPolicy(2000, List.of(glob("/tmp/**"))));
        // then
        assertThat(next.entries().keySet()).containsExactlyInAnyOrder(wsl("/tmp/keep"), wsl("/home/me"));
        assertThat(next.policy().exclude()).containsExactly(glob("/tmp/**"));
    }

    @Test
    @DisplayName("maxEntries 3 · 항목 5개(고정 1)에 정책을 바꾸면 고정 1 + 최근 2 가 남는다")
    void withPolicyPrunesOldestUnpinned() {
        // given
        WorkHistory h = WorkHistory.empty(HistoryPolicy.DEFAULT);
        for (int i = 1; i <= 5; i++) {
            h = h.recordVisit(wsl("/p" + i), at(i));
        }
        h = pinned(h, wsl("/p1"));
        // when
        WorkHistory next = h.withPolicy(new HistoryPolicy(3));
        // then
        assertThat(next.entries().keySet()).containsExactlyInAnyOrder(wsl("/p1"), wsl("/p4"), wsl("/p5"));
        assertThat(next.policy().maxEntries()).isEqualTo(3);
    }

    @Test
    @DisplayName("고정 항목이 한도보다 많으면 고정 항목만 남는다")
    void withPolicyPinnedOverLimit() {
        // given
        WorkHistory h = WorkHistory.empty(HistoryPolicy.DEFAULT)
                .recordVisit(wsl("/a"), T1)
                .recordVisit(wsl("/b"), at(1))
                .recordVisit(wsl("/c"), at(2));
        h = pinned(pinned(h, wsl("/a")), wsl("/b"));
        // when
        WorkHistory next = h.withPolicy(new HistoryPolicy(1));
        // then
        assertThat(next.entries().keySet()).containsExactlyInAnyOrder(wsl("/a"), wsl("/b"));
    }

    @Test
    @DisplayName("저장된 항목을 불러올 때도 제외 규칙에 맞는 고정 안 된 항목은 버린다")
    void restoreAppliesExclude() {
        // given
        List<VisitedDirectory> saved = List.of(
                new VisitedDirectory(wsl("/tmp/a"), T1, T1, 1, false, Optional.empty(), false),
                new VisitedDirectory(wsl("/tmp/b"), T1, T1, 1, true, Optional.empty(), false),
                new VisitedDirectory(wsl("/home"), T1, T1, 1, false, Optional.empty(), false));
        // when
        WorkHistory h = WorkHistory.restore(saved, new HistoryPolicy(10, List.of(glob("/tmp/**"))));
        // then
        assertThat(h.entries().keySet()).containsExactlyInAnyOrder(wsl("/tmp/b"), wsl("/home"));
    }
}
