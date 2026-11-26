package dev.tanager.domain.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PinnedEntryTest {

    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

    private static VisitedDirectory entry() {
        WorkspacePath p = WorkspacePath.of(new ShellEnvironment.Wsl("U"), "/home/me")
                .value()
                .orElseThrow();
        return VisitedDirectory.firstVisit(p, T);
    }

    @Test
    @DisplayName("항목과 비지 않은 라벨로 만들어진다")
    void valid() {
        // given
        VisitedDirectory v = entry();

        // when
        PinnedEntry pinned = new PinnedEntry(v, "me", true);

        // then
        assertThat(pinned.entry()).isEqualTo(v);
        assertThat(pinned.label()).isEqualTo("me");
        assertThat(pinned.matched()).isTrue();
    }

    @Test
    @DisplayName("빈 라벨 · 공백뿐인 라벨은 거부한다")
    void rejectsBlankLabel() {
        // given
        VisitedDirectory v = entry();

        // when · then
        assertThatThrownBy(() -> new PinnedEntry(v, "", false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PinnedEntry(v, "  ", false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("항목이나 라벨이 null 이면 거부한다")
    void rejectsNull() {
        // given
        VisitedDirectory v = entry();

        // when · then
        assertThatThrownBy(() -> new PinnedEntry(null, "me", false))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("entry");
        assertThatThrownBy(() -> new PinnedEntry(v, null, false))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("label");
    }
}
