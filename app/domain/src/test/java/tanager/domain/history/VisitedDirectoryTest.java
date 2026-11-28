package tanager.domain.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VisitedDirectoryTest {

    private static final WorkspacePath P =
            WorkspacePath.of(new ShellEnvironment.Wsl("Ubuntu"), "/a").value().orElseThrow();
    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-01-02T00:00:00Z");

    @Test
    @DisplayName("방문 횟수가 0 이면 IAE")
    void rejectsZeroCount() {
        // given / when / then
        assertThatThrownBy(() -> new VisitedDirectory(P, T1, T2, 0, false, Optional.empty(), false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("최초 방문이 최근 방문보다 늦으면 IAE")
    void rejectsFirstAfterLast() {
        // given / when / then
        assertThatThrownBy(() -> new VisitedDirectory(P, T2, T1, 1, false, Optional.empty(), false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("고정이면서 숨김인 항목은 IAE")
    void rejectsPinnedAndHidden() {
        // given / when / then
        assertThatThrownBy(() -> new VisitedDirectory(P, T1, T2, 1, true, Optional.empty(), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("first 와 last 가 같은 항목은 만들 수 있다")
    void acceptsEqualFirstAndLast() {
        // given / when
        var v = new VisitedDirectory(P, T1, T1, 1, false, Optional.empty(), false);
        // then
        assertThat(v.visitCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("출처 없이 만들면 출처는 shell 하나다")
    void defaultSourceIsShell() {
        // given / when
        var v = new VisitedDirectory(P, T1, T2, 1, false, Optional.empty(), false);
        // then
        assertThat(v.sources()).containsExactly(VisitSource.SHELL);
        assertThat(v.usedBy(VisitSource.SHELL)).isTrue();
        assertThat(v.usedBy(VisitSource.CLAUDE_CODE)).isFalse();
    }

    @Test
    @DisplayName("출처가 비면 IAE")
    void rejectsEmptySources() {
        // given / when / then
        assertThatThrownBy(() -> new VisitedDirectory(P, T1, T2, 1, false, Optional.empty(), false, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("출처는 id 순으로 정렬된 불변 집합이다")
    void sourcesSortedAndImmutable() {
        // given
        Set<VisitSource> given = new LinkedHashSet<>(
                List.of(VisitSource.SHELL, new VisitSource("future-tool"), VisitSource.CLAUDE_CODE));

        // when
        var v = new VisitedDirectory(P, T1, T2, 1, false, Optional.empty(), false, given);
        given.clear();

        // then
        assertThat(v.sources())
                .containsExactly(VisitSource.CLAUDE_CODE, new VisitSource("future-tool"), VisitSource.SHELL);
        assertThatThrownBy(() -> v.sources().add(new VisitSource("x")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("같은 출처 집합이면 순서와 무관하게 같은 항목이다")
    void equalityIgnoresInsertionOrder() {
        // given / when
        var a = new VisitedDirectory(
                P, T1, T2, 1, false, Optional.empty(), false, Set.of(VisitSource.SHELL, VisitSource.CLAUDE_CODE));
        var b = new VisitedDirectory(
                P,
                T1,
                T2,
                1,
                false,
                Optional.empty(),
                false,
                new LinkedHashSet<>(List.of(VisitSource.CLAUDE_CODE, VisitSource.SHELL)));
        // then
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }
}
