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
        AppEvent notice = new AppEvent.StoreNotice(new AppEvent.StoreProblem.Loaded(
                new tanager.application.port.out.HistoryLoadNotice.RecoveredFromBackup(java.util.Optional.empty())));

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

    @Test
    @DisplayName("설정을 다시 읽지 못한 알림은 상태줄 문장이 되고, 설정이 바뀐 이벤트는 보이지 않는다")
    void settingsNoticeBecomesStatusMessage() {
        // given
        AppEvent kept = new AppEvent.SettingsNotice(new AppEvent.SettingsProblem.KeptPrevious(
                new tanager.application.port.out.ConfigError.Corrupt("(1:2)")));
        AppEvent changed = new AppEvent.SettingsChanged(tanager.application.port.out.AppConfig.defaults());

        // when / then
        assertThat(Notices.messageOf(kept)).contains("설정 파일을 읽지 못해 이전 설정을 쓴다: 설정 파일이 손상됐다: (1:2)");
        assertThat(Notices.messageOf(changed)).isEmpty();
    }

    @Test
    @DisplayName("창 상태가 바뀐 이벤트는 상태줄에 보이지 않는다")
    void windowChangedIsNotStatusMessage() {
        // given
        AppEvent changed = new AppEvent.WindowChanged(tanager.domain.workspace.WindowState.DEFAULT);

        // when / then
        assertThat(Notices.messageOf(changed)).isEmpty();
    }

    @Test
    @DisplayName("대화 가져오기 알림은 상태줄 문장이 되고, 대화 목록이 바뀐 이벤트는 보이지 않는다(#215)")
    void importNoticeBecomesStatusMessage() {
        // given
        var origin = new tanager.domain.history.ImportOrigin(
                tanager.domain.history.VisitSource.CLAUDE_CODE, new tanager.domain.history.ShellEnvironment.Windows());
        AppEvent notice = new AppEvent.ImportNotice(new AppEvent.ImportProblem.UnknownFormat(origin));
        AppEvent changed = new AppEvent.ConversationsChanged(java.util.Map.of());

        // when / then
        assertThat(Notices.messageOf(notice))
                .hasValueSatisfying(text -> assertThat(text).contains("Claude Code"));
        assertThat(Notices.messageOf(changed)).isEmpty();
    }

    @Test
    @DisplayName("창의 시작 명령을 건너뛴 알림은 상태줄 문장이 된다(#216)")
    void sessionNoticeBecomesStatusMessage() {
        // given
        AppEvent notice = new AppEvent.SessionNotice(
                new tanager.domain.shared.PaneId("p2"),
                new tanager.application.port.in.StartNotice.StartupCommandSkipped(
                        new tanager.domain.session.ShellLine("claude --continue"),
                        tanager.application.port.in.StartNotice.StartupCommandSkipped.Reason.TIMED_OUT));

        // when / then
        assertThat(Notices.messageOf(notice))
                .hasValueSatisfying(text -> assertThat(text).contains("claude --continue"));
    }
}
