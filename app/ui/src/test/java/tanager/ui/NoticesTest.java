package tanager.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.in.AppEvent;
import tanager.domain.history.HistoryPolicy;
import tanager.domain.history.WorkHistory;

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

    @Test
    @DisplayName("저장소 알림 이벤트는 상태줄 문장이 되고 같은 문장은 한 번만 쌓인다")
    void storeNoticeBecomesStatusMessageOnce() {
        // given
        AppEvent notice = new AppEvent.StoreNotice("이력 파일이 손상돼 백업에서 복구했다");

        // when
        Notices notices = new Notices(List.of())
                .add(Notices.messageOf(notice).stream().toList())
                .add(Notices.messageOf(notice).stream().toList());

        // then
        assertThat(notices.messages()).containsExactly("이력 파일이 손상돼 백업에서 복구했다");
    }

    @Test
    @DisplayName("저장소 알림이 아닌 이벤트는 상태줄에 보이지 않는다")
    void otherEventsAreNotStatusMessages() {
        // given
        AppEvent changed = new AppEvent.HistoryChanged(WorkHistory.empty(HistoryPolicy.DEFAULT));

        // when / then
        assertThat(Notices.messageOf(changed)).isEmpty();
    }
}
