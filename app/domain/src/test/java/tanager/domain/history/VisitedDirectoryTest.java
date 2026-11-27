package tanager.domain.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;
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
}
