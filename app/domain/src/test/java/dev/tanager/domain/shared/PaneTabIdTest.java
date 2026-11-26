package dev.tanager.domain.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaneTabIdTest {

    @Test
    @DisplayName("PaneId 는 같은 값이면 같다")
    void paneIdEquality() {
        assertThat(new PaneId("p1")).isEqualTo(new PaneId("p1")).isNotEqualTo(new PaneId("p2"));
    }

    @Test
    @DisplayName("PaneId 는 공백 값을 거부한다")
    void paneIdRejectsBlank() {
        assertThatThrownBy(() -> new PaneId(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaneId("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("TabId 는 같은 값이면 같고 공백 값을 거부한다")
    void tabId() {
        assertThat(new TabId("t1")).isEqualTo(new TabId("t1"));
        assertThatThrownBy(() -> new TabId("  ")).isInstanceOf(IllegalArgumentException.class);
    }
}
