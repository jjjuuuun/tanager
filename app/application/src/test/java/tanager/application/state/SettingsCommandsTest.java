package tanager.application.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.in.AppEvent;
import tanager.application.port.out.AppConfig;
import tanager.application.port.out.ConfigError;
import tanager.application.port.out.ConfigWarning;
import tanager.application.port.out.LoadedConfig;
import tanager.application.port.out.TerminalConfig;
import tanager.domain.history.HistoryPolicy;
import tanager.domain.history.WorkHistory;

/** 설정을 읽은 결과를 상태에 반영하는 명령(FR-CFG-03, ADR-0009 개정). */
class SettingsCommandsTest {
    private static final AppState START = AppState.initial(WorkHistory.empty(HistoryPolicy.DEFAULT));
    private static final ConfigWarning SIZE_CLAMPED = new ConfigWarning.OutOfRange("terminal.size", "200", "32");
    private static final ConfigError CORRUPT = new ConfigError.Corrupt("(3:5)");

    private static AppConfig withSize(int size) {
        AppConfig base = AppConfig.defaults();
        return new AppConfig(
                base.defaultProfile(),
                base.profiles(),
                base.openPlacement(),
                base.appearance(),
                new TerminalConfig("D2Coding", size, 1.2, 10_000),
                base.history(),
                base.monitor(),
                base.keys());
    }

    private static LoadedConfig loaded(AppConfig config, ConfigWarning... warnings) {
        return new LoadedConfig(config, List.of(warnings));
    }

    @Test
    @DisplayName("시작 상태에는 아직 읽은 설정이 없다")
    void initialStateHasNoSettings() {
        assertThat(START.settings()).isEmpty();
    }

    @Test
    @DisplayName("처음 읽으면 기본값과 같아도 설정을 두고 SettingsChanged 를 낸다 — 시작 경고는 셸 시작 알림이 보인다")
    void firstLoadAlwaysAnnounces() {
        // when
        Transition<Boolean> t = new SettingsLoaded(loaded(AppConfig.defaults(), SIZE_CLAMPED)).apply(START);

        // then
        assertThat(t.next().settings()).contains(AppConfig.defaults());
        assertThat(t.events()).containsExactly(new AppEvent.SettingsChanged(AppConfig.defaults()));
        assertThat(t.reply()).isTrue();
    }

    @Test
    @DisplayName("바뀐 설정을 읽으면 바꾸고 SettingsChanged 뒤에 경고를 알린다")
    void changedReloadAnnouncesWithWarnings() {
        // given
        AppState current = START.withSettings(AppConfig.defaults());

        // when
        Transition<Boolean> t = new SettingsLoaded(loaded(withSize(32), SIZE_CLAMPED)).apply(current);

        // then
        assertThat(t.next().settings()).contains(withSize(32));
        assertThat(t.events())
                .containsExactly(
                        new AppEvent.SettingsChanged(withSize(32)),
                        new AppEvent.SettingsNotice(new AppEvent.SettingsProblem.Warned(SIZE_CLAMPED)));
        assertThat(t.reply()).isTrue();
    }

    @Test
    @DisplayName("같은 설정을 다시 읽으면 상태를 그대로 두고 이벤트가 없다")
    void sameReloadIsSilent() {
        // given
        AppState current = START.withSettings(withSize(16));

        // when
        Transition<Boolean> t = new SettingsLoaded(loaded(withSize(16), SIZE_CLAMPED)).apply(current);

        // then
        assertThat(t.next()).isSameAs(current);
        assertThat(t.events()).isEmpty();
        assertThat(t.reply()).isFalse();
    }

    @Test
    @DisplayName("다시 읽다 실패하면 마지막 정상 설정을 그대로 쓰고 이전 설정을 쓴다고 알린다")
    void failedReloadKeepsPrevious() {
        // given
        AppState current = START.withSettings(withSize(16));

        // when
        Transition<Boolean> t = new SettingsLoadFailed(CORRUPT).apply(current);

        // then
        assertThat(t.next()).isSameAs(current);
        assertThat(t.events())
                .containsExactly(new AppEvent.SettingsNotice(new AppEvent.SettingsProblem.KeptPrevious(CORRUPT)));
        assertThat(t.reply()).isFalse();
    }

    @Test
    @DisplayName("시작할 때 읽지 못하면 기본값으로 SettingsChanged 를 한 번 낸다 — 읽지 못한 이유는 셸 시작 알림이 보인다")
    void failedFirstLoadUsesDefaults() {
        // when
        Transition<Boolean> t = new SettingsLoadFailed(CORRUPT).apply(START);

        // then
        assertThat(t.next().settings()).contains(AppConfig.defaults());
        assertThat(t.events()).containsExactly(new AppEvent.SettingsChanged(AppConfig.defaults()));
        assertThat(t.reply()).isTrue();
    }

    @Test
    @DisplayName("설정을 바꿔도 다른 상태는 그대로다")
    void settingsChangeKeepsOtherState() {
        // when
        AppState next = START.withSettings(withSize(20));

        // then
        assertThat(next.history()).isEqualTo(START.history());
        assertThat(next.workspace()).isEqualTo(START.workspace());
        assertThat(next.store()).isEqualTo(START.store());
        assertThat(next.withHistory(START.history()).settings()).contains(withSize(20));
    }
}
