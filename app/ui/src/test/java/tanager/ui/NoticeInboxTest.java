package tanager.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NoticeInboxTest {
    @Test
    @DisplayName("창이 붙기 전 알림은 모았다가 붙을 때 순서대로 한 번에 넘기고 이후 알림은 바로 넘긴다")
    void buffersUntilAttachedThenForwards() {
        // given
        NoticeInbox inbox = new NoticeInbox();
        List<List<String>> received = new ArrayList<>();
        inbox.accept("백업에서 복구했다");
        inbox.accept("읽기만 한다");

        // when
        inbox.attach(received::add);
        inbox.accept("저장하지 못했다");

        // then
        assertThat(received).containsExactly(List.of("백업에서 복구했다", "읽기만 한다"), List.of("저장하지 못했다"));
    }

    @Test
    @DisplayName("모아 둔 알림이 없으면 붙일 때 아무것도 넘기지 않는다")
    void attachWithoutBufferedSendsNothing() {
        // given
        NoticeInbox inbox = new NoticeInbox();
        List<List<String>> received = new ArrayList<>();

        // when
        inbox.attach(received::add);

        // then
        assertThat(received).isEmpty();
    }
}
