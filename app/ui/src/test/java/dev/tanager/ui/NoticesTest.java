package dev.tanager.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NoticesTest {
    @Test
    @DisplayName("같은 문장은 한 번만 쌓고 요약은 첫 문장 (+나머지 수)다")
    void addsEachMessageOnceAndSummarizes() {
        // when
        Notices notices = new Notices(List.of("a")).add(List.of("a", "b"));

        // then
        assertThat(notices.messages()).containsExactly("a", "b");
        assertThat(notices.summary()).contains("a (+1)");
    }

    @Test
    @DisplayName("알림이 없으면 요약도 없다")
    void emptyHasNoSummary() {
        assertThat(new Notices(List.of()).summary()).isEmpty();
    }
}
