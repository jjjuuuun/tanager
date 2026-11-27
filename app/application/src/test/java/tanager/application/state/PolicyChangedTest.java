package tanager.application.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.in.AppEvent;
import tanager.application.port.out.AppConfig;
import tanager.application.port.out.ConfigError;
import tanager.application.port.out.HistoryConfig;
import tanager.application.port.out.LoadedConfig;
import tanager.application.port.out.LoadedHistory;
import tanager.application.port.out.StoreAccess;
import tanager.domain.history.HistoryPolicy;
import tanager.domain.history.PathPattern;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkHistory;
import tanager.domain.history.WorkspacePath;

/** 설정의 이력 규칙(maxEntries · exclude)을 이력에 반영하는 명령(FR-HIS-09, #186). */
class PolicyChangedTest {
    private static final ShellEnvironment UBUNTU = new ShellEnvironment.Wsl("Ubuntu");
    private static final Instant T0 = Instant.parse("2026-11-28T00:00:00Z");

    private static WorkspacePath wsl(String raw) {
        return WorkspacePath.of(UBUNTU, raw).value().orElseThrow();
    }

    private static PathPattern glob(String raw) {
        return PathPattern.of(raw).value().orElseThrow();
    }

    private static WorkHistory visited(int count) {
        WorkHistory h = WorkHistory.empty(HistoryPolicy.DEFAULT);
        for (int i = 0; i < count; i++) {
            h = h.recordVisit(wsl("/p" + i), T0.plusSeconds(i));
        }
        return h;
    }

    private static AppConfig withHistory(HistoryConfig history) {
        AppConfig base = AppConfig.defaults();
        return new AppConfig(
                base.defaultProfile(),
                base.profiles(),
                base.openPlacement(),
                base.appearance(),
                base.terminal(),
                history,
                base.monitor(),
                base.keys());
    }

    @Test
    @DisplayName("설정의 이력 항목을 정책으로 바꾼다")
    void fromConfig() {
        // when
        HistoryPolicy policy = PolicyChanged.policyOf(new HistoryConfig(500, List.of("/tmp/**", "**/x/**")));
        // then
        assertThat(policy).isEqualTo(new HistoryPolicy(500, List.of(glob("/tmp/**"), glob("**/x/**"))));
    }

    @Test
    @DisplayName("glob 으로 읽을 수 없는 제외 규칙은 그것만 버린다")
    void dropsInvalidGlob() {
        // when
        HistoryPolicy policy = PolicyChanged.policyOf(new HistoryConfig(500, List.of("tmp/**", "/tmp/**")));
        // then
        assertThat(policy.exclude()).containsExactly(glob("/tmp/**"));
    }

    @Test
    @DisplayName("한도를 줄이면 이력을 줄이고 HistoryChanged 를 낸다")
    void shrinkAnnounces() {
        // given
        AppState current = AppState.initial(visited(5));
        // when
        Transition<Boolean> t = new PolicyChanged(new HistoryPolicy(3)).apply(current);
        // then
        WorkHistory next = t.next().history();
        assertThat(next.entries().keySet()).containsExactlyInAnyOrder(wsl("/p2"), wsl("/p3"), wsl("/p4"));
        assertThat(t.events()).containsExactly(new AppEvent.HistoryChanged(next));
        assertThat(t.reply()).isTrue();
    }

    @Test
    @DisplayName("항목이 그대로면 정책만 바꾸고 이벤트를 내지 않는다")
    void sameEntriesSilent() {
        // given
        AppState current = AppState.initial(visited(2));
        HistoryPolicy policy = new HistoryPolicy(100, List.of(glob("/tmp/**")));
        // when
        Transition<Boolean> t = new PolicyChanged(policy).apply(current);
        // then
        assertThat(t.next().history().policy()).isEqualTo(policy);
        assertThat(t.next().history().entries()).isEqualTo(current.history().entries());
        assertThat(t.events()).isEmpty();
    }

    @Test
    @DisplayName("같은 정책이면 상태를 바꾸지 않는다")
    void samePolicyNoop() {
        // given
        AppState current = AppState.initial(visited(2));
        // when
        Transition<Boolean> t = new PolicyChanged(HistoryPolicy.DEFAULT).apply(current);
        // then
        assertThat(t.next()).isSameAs(current);
        assertThat(t.events()).isEmpty();
        assertThat(t.reply()).isFalse();
    }

    @Test
    @DisplayName("설정을 다시 읽어 maxEntries 가 줄면 SettingsChanged 뒤에 줄어든 이력으로 HistoryChanged 를 낸다")
    void settingsReloadShrinksHistory() {
        // given
        AppState current = AppState.initial(visited(105)).withSettings(AppConfig.defaults());
        AppConfig smaller = withHistory(new HistoryConfig(100, List.of()));
        // when
        Transition<Boolean> t = new SettingsLoaded(new LoadedConfig(smaller, List.of())).apply(current);
        // then
        WorkHistory next = t.next().history();
        assertThat(next.entries()).hasSize(100);
        assertThat(next.find(wsl("/p0"))).isEmpty();
        assertThat(t.events())
                .containsExactly(new AppEvent.SettingsChanged(smaller), new AppEvent.HistoryChanged(next));
    }

    @Test
    @DisplayName("처음 읽다 실패해도 기본 설정의 제외 규칙(/tmp/**)을 이력에 반영한다")
    void firstLoadFailureAppliesDefaults() {
        // given
        WorkHistory h = WorkHistory.empty(HistoryPolicy.DEFAULT)
                .recordVisit(wsl("/tmp/x"), T0)
                .recordVisit(wsl("/home/me"), T0);
        // when
        Transition<Boolean> t = new SettingsLoadFailed(new ConfigError.Corrupt("(1:1)")).apply(AppState.initial(h));
        // then
        WorkHistory next = t.next().history();
        assertThat(next.entries().keySet()).containsExactly(wsl("/home/me"));
        assertThat(t.events())
                .containsExactly(new AppEvent.SettingsChanged(AppConfig.defaults()), new AppEvent.HistoryChanged(next));
    }

    @Test
    @DisplayName("불러온 이력에도 지금 정책을 적용한다 — 설정이 먼저 반영돼도 파일의 제외 경로가 살아나지 않는다")
    void loadedHistoryFollowsCurrentPolicy() {
        // given
        HistoryPolicy policy = new HistoryPolicy(2000, List.of(glob("/tmp/**")));
        AppState current = AppState.initial(WorkHistory.empty(policy));
        WorkHistory fromFile = WorkHistory.empty(HistoryPolicy.DEFAULT)
                .recordVisit(wsl("/tmp/x"), T0)
                .recordVisit(wsl("/home/me"), T0);
        // when
        Transition<Boolean> t =
                new HistoryLoaded(new LoadedHistory(fromFile, StoreAccess.WRITABLE, List.of())).apply(current);
        // then
        assertThat(t.next().history().policy()).isEqualTo(policy);
        assertThat(t.next().history().entries().keySet()).containsExactly(wsl("/home/me"));
    }
}
