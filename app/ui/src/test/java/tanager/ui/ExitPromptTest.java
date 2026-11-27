package tanager.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 앱 창 닫기(X) 때 물을지(#89, U2). */
class ExitPromptTest {

    @Test
    @DisplayName("실행 중 셸이 2개 이상이면 묻고, 0 · 1 개면 바로 끝낸다")
    void asksOnlyForTwoOrMore() {
        assertThat(ExitPrompt.needed(0)).isFalse();
        assertThat(ExitPrompt.needed(1)).isFalse();
        assertThat(ExitPrompt.needed(2)).isTrue();
        assertThat(ExitPrompt.needed(5)).isTrue();
    }
}
