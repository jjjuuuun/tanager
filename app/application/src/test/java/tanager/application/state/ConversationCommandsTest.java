package tanager.application.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.in.AppEvent;
import tanager.application.port.out.AppConfig;
import tanager.application.port.out.ConversationScan;
import tanager.application.port.out.ToolkitConfig;
import tanager.domain.history.ConversationId;
import tanager.domain.history.HistoryPolicy;
import tanager.domain.history.ImportOrigin;
import tanager.domain.history.PastConversation;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.VisitSource;
import tanager.domain.history.VisitedDirectory;
import tanager.domain.history.WorkHistory;
import tanager.domain.history.WorkspacePath;

/** 대화 가져오기 명령 — 이력 합치기 · 메모리 대화 목록 · 이벤트(FR-TK-09, #215). */
class ConversationCommandsTest {
    private static final ShellEnvironment WIN = new ShellEnvironment.Windows();
    private static final ShellEnvironment UBUNTU = new ShellEnvironment.Wsl("Ubuntu");
    private static final ImportOrigin WIN_CLAUDE = new ImportOrigin(VisitSource.CLAUDE_CODE, WIN);
    private static final ImportOrigin UBUNTU_CLAUDE = new ImportOrigin(VisitSource.CLAUDE_CODE, UBUNTU);
    private static final Instant T0 = Instant.parse("2026-11-28T00:00:00Z");
    private static final WorkspacePath WORK = path(WIN, "C:\\work");
    private static final PastConversation OLDER = conversation("a1", WORK, T0.minus(Duration.ofDays(2)));
    private static final PastConversation NEWER = conversation("a2", WORK, T0.minus(Duration.ofHours(1)));

    private static WorkspacePath path(ShellEnvironment env, String raw) {
        return WorkspacePath.of(env, raw).value().orElseThrow();
    }

    private static PastConversation conversation(String id, WorkspacePath cwd, Instant lastActive) {
        return new PastConversation(
                VisitSource.CLAUDE_CODE, new ConversationId(id), cwd, Optional.of("요약 " + id), lastActive);
    }

    private static AppConfig withToolkit(ToolkitConfig toolkit) {
        AppConfig d = AppConfig.defaults();
        return new AppConfig(
                d.defaultProfile(),
                d.profiles(),
                d.openPlacement(),
                d.appearance(),
                d.terminal(),
                d.history(),
                d.monitor(),
                d.keys(),
                toolkit);
    }

    private static AppState ready() {
        return AppState.initial(WorkHistory.empty(HistoryPolicy.DEFAULT)).withSettings(AppConfig.defaults());
    }

    private static ConversationsScanned scanned(PastConversation... conversations) {
        return new ConversationsScanned(WIN_CLAUDE, T0, new ConversationScan(List.of(conversations), 0));
    }

    @Test
    @DisplayName("읽은 대화의 폴더가 claude-code 출처로 이력에 생기고(처음 = 가장 오래된, 마지막 = 가장 최근) 대화 목록이 메모리에 놓인다")
    void importsFoldersAndKeepsConversations() {
        // when
        Transition<Boolean> t = scanned(NEWER, OLDER).apply(ready());

        // then
        VisitedDirectory work = t.next().history().find(WORK).orElseThrow();
        assertThat(work.sources()).contains(VisitSource.CLAUDE_CODE);
        assertThat(work.firstVisited()).isEqualTo(OLDER.lastActive());
        assertThat(work.lastVisited()).isEqualTo(NEWER.lastActive());
        assertThat(t.next().conversations()).isEqualTo(Map.of(WIN_CLAUDE, List.of(NEWER, OLDER)));
        assertThat(t.events())
                .containsExactly(
                        new AppEvent.HistoryChanged(t.next().history()),
                        new AppEvent.ConversationsChanged(Map.of(WIN_CLAUDE, List.of(NEWER, OLDER))));
        assertThat(t.reply()).isTrue();
    }

    @Test
    @DisplayName("같은 기록을 두 번 읽으면 두 번째에는 이벤트가 나가지 않는다(이력 항목 · 대화가 같다)")
    void sameScanTwiceIsQuiet() {
        // given
        AppState once = scanned(NEWER, OLDER).apply(ready()).next();

        // when
        Transition<Boolean> again = new ConversationsScanned(
                        WIN_CLAUDE, T0.plus(Duration.ofMinutes(5)), new ConversationScan(List.of(NEWER, OLDER), 0))
                .apply(once);

        // then
        assertThat(again.events()).isEmpty();
        assertThat(again.next().history().entries()).isEqualTo(once.history().entries());
        assertThat(again.reply()).isFalse();
    }

    @Test
    @DisplayName("다른 환경의 대화는 그 origin 목록에 넣지 않는다")
    void dropsConversationsOfOtherEnvironment() {
        // given
        PastConversation stray = conversation("w1", path(UBUNTU, "/home/me/x"), T0.minusSeconds(10));

        // when
        Transition<Boolean> t = scanned(NEWER, stray).apply(ready());

        // then
        assertThat(t.next().conversations().get(WIN_CLAUDE)).containsExactly(NEWER);
        assertThat(t.next().history().find(path(UBUNTU, "/home/me/x"))).isEmpty();
    }

    @Test
    @DisplayName("설정이 대화 가져오기를 끈 뒤 도착한 결과는 버린다")
    void dropsResultWhenImportIsOff() {
        // given
        AppState off = ready().withSettings(withToolkit(new ToolkitConfig(List.of(), false)));

        // when
        Transition<Boolean> t = scanned(NEWER).apply(off);

        // then
        assertThat(t.next()).isEqualTo(off);
        assertThat(t.events()).isEmpty();
    }

    @Test
    @DisplayName("설정을 읽기 전 결과는 버린다(켜져 있는지 모른다)")
    void dropsResultBeforeSettings() {
        // given
        AppState unknown = AppState.initial(WorkHistory.empty(HistoryPolicy.DEFAULT));

        // when
        Transition<Boolean> t = scanned(NEWER).apply(unknown);

        // then
        assertThat(t.next()).isEqualTo(unknown);
        assertThat(t.events()).isEmpty();
    }

    @Test
    @DisplayName("목록을 지우면 대화만 비고 이력의 claude-code 출처는 그대로다")
    void clearKeepsHistorySources() {
        // given
        AppState imported = scanned(NEWER).apply(ready()).next();

        // when
        Transition<Boolean> t = new ConversationsCleared(Optional.of(WIN_CLAUDE)).apply(imported);

        // then
        assertThat(t.next().conversations()).isEmpty();
        assertThat(t.next().history()).isEqualTo(imported.history());
        assertThat(t.events()).containsExactly(new AppEvent.ConversationsChanged(Map.of()));
    }

    @Test
    @DisplayName("origin 없이 지우면 모두 지우고, 지울 것이 없으면 이벤트가 없다")
    void clearAllAndNothingToClear() {
        // given
        AppState two = new ConversationsScanned(
                        UBUNTU_CLAUDE,
                        T0,
                        new ConversationScan(
                                List.of(conversation("u1", path(UBUNTU, "/home/me/w"), T0.minusSeconds(1))), 0))
                .apply(scanned(NEWER).apply(ready()).next())
                .next();

        // when
        Transition<Boolean> all = new ConversationsCleared(Optional.empty()).apply(two);
        Transition<Boolean> none = new ConversationsCleared(Optional.empty()).apply(all.next());

        // then
        assertThat(all.next().conversations()).isEmpty();
        assertThat(all.events()).containsExactly(new AppEvent.ConversationsChanged(Map.of()));
        assertThat(none.events()).isEmpty();
        assertThat(none.reply()).isFalse();
    }

    @Test
    @DisplayName("가져올 수 없게 되면 그 origin 의 목록을 지우고 ImportNotice 를 낸다")
    void unavailableClearsAndNotifies() {
        // given
        AppState imported = scanned(NEWER).apply(ready()).next();
        AppEvent.ImportProblem problem = new AppEvent.ImportProblem.UnknownFormat(WIN_CLAUDE);

        // when
        Transition<Boolean> t = new ConversationsUnavailable(problem).apply(imported);

        // then
        assertThat(t.next().conversations()).isEmpty();
        assertThat(t.events())
                .containsExactly(new AppEvent.ConversationsChanged(Map.of()), new AppEvent.ImportNotice(problem));
    }
}
