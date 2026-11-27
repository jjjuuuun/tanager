package tanager.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.in.AppEvent;
import tanager.application.port.out.AppConfig;
import tanager.application.port.out.ConfigError;
import tanager.application.port.out.InMemoryConfigRepository;
import tanager.application.port.out.LoadedConfig;
import tanager.application.port.out.ManualConfigWatch;
import tanager.application.port.out.Subscription;
import tanager.application.port.out.TerminalConfig;
import tanager.application.state.AppState;
import tanager.application.state.ManualExecutor;
import tanager.application.state.StateActor;
import tanager.domain.history.HistoryPolicy;
import tanager.domain.history.WorkHistory;
import tanager.domain.shared.Result;

/** 설정 파일이 바뀌면 다시 읽어 상태에 반영한다(FR-CFG-03, ADR-0009 개정). */
class SettingsReloaderTest {
    private static final ConfigError CORRUPT = new ConfigError.Corrupt("(1:3)");

    private final ManualExecutor executor = new ManualExecutor();
    private final StateActor state =
            new StateActor(AppState.initial(WorkHistory.empty(HistoryPolicy.DEFAULT)), executor);
    private final InMemoryConfigRepository config = new InMemoryConfigRepository();
    private final ManualConfigWatch watch = new ManualConfigWatch();
    private final SettingsReloader reloader = new SettingsReloader(config, state);
    private final List<AppEvent> events = new ArrayList<>();

    SettingsReloaderTest() {
        state.subscribe(events::add);
    }

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

    private void fileHas(AppConfig value) {
        config.willLoad(Result.ok(new LoadedConfig(value, List.of())));
    }

    /** 편집기로 저장한 것처럼 알리고 명령을 처리한다. */
    private void saved() {
        watch.fire();
        executor.runAll();
    }

    private List<AppEvent> settingsEvents() {
        return events.stream()
                .filter(e -> e instanceof AppEvent.SettingsChanged || e instanceof AppEvent.SettingsNotice)
                .toList();
    }

    @Test
    @DisplayName("시작하면 감시를 걸고 설정을 한 번 읽어 SettingsChanged 를 한 번 낸다")
    void startWatchesAndAnnouncesOnce() {
        // given
        fileHas(withSize(14));

        // when
        reloader.start(watch);
        executor.runAll();

        // then
        assertThat(watch.watchers()).isEqualTo(1);
        assertThat(settingsEvents()).containsExactly(new AppEvent.SettingsChanged(withSize(14)));
        assertThat(reloader.current()).isEqualTo(withSize(14));
    }

    @Test
    @DisplayName("실행 중 terminal.size 를 16 으로 저장하면 SettingsChanged(size 16) 가 한 번 나간다")
    void changedFileAnnouncesNewConfig() {
        // given
        fileHas(withSize(14));
        reloader.start(watch);
        executor.runAll();
        events.clear();

        // when
        fileHas(withSize(16));
        saved();

        // then
        assertThat(settingsEvents()).containsExactly(new AppEvent.SettingsChanged(withSize(16)));
        assertThat(reloader.current().terminal().size()).isEqualTo(16);
    }

    @Test
    @DisplayName("같은 내용으로 다시 저장하면 이벤트가 없다")
    void sameContentIsSilent() {
        // given
        fileHas(withSize(14));
        reloader.start(watch);
        executor.runAll();
        events.clear();

        // when
        saved();

        // then
        assertThat(settingsEvents()).isEmpty();
    }

    @Test
    @DisplayName("문법 오류로 저장하면 마지막 정상 설정을 쓰고 알리며, 고쳐 저장하면 다시 반영한다")
    void corruptFileKeepsPreviousUntilFixed() {
        // given
        fileHas(withSize(16));
        reloader.start(watch);
        executor.runAll();
        events.clear();

        // when 손상
        config.willFailLoad(CORRUPT);
        saved();

        // then
        assertThat(settingsEvents())
                .containsExactly(new AppEvent.SettingsNotice(new AppEvent.SettingsProblem.KeptPrevious(CORRUPT)));
        assertThat(reloader.current()).isEqualTo(withSize(16));

        // when 고침
        events.clear();
        fileHas(withSize(18));
        saved();

        // then
        assertThat(settingsEvents()).containsExactly(new AppEvent.SettingsChanged(withSize(18)));
        assertThat(reloader.current()).isEqualTo(withSize(18));
    }

    @Test
    @DisplayName("읽기 전에는 지금 설정이 기본값이다")
    void currentBeforeLoadIsDefaults() {
        assertThat(reloader.current()).isEqualTo(AppConfig.defaults());
    }

    @Test
    @DisplayName("구독을 끊으면 파일이 바뀌어도 다시 읽지 않는다")
    void cancelledWatchStopsReloading() {
        // given
        Subscription subscription = reloader.start(watch);
        executor.runAll();
        int loads = config.loadCalls();

        // when
        subscription.cancel();
        saved();

        // then
        assertThat(config.loadCalls()).isEqualTo(loads);
        assertThat(watch.watchers()).isZero();
    }

    @Test
    @DisplayName("file 은 저장소의 설정 파일을 만들어(없으면) 그 위치를 돌려준다")
    void fileEnsuresConfigFile() {
        // when
        Result<Path, ConfigError> file = reloader.file();

        // then
        assertThat(file.value()).contains(config.location());
        assertThat(config.ensureCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("읽다가 예외가 나도 감시 콜백은 던지지 않고 다음 변경을 다시 읽는다")
    void loadExceptionDoesNotBreakWatching() {
        // given
        reloader.start(watch);
        executor.runAll();
        config.willThrowOnLoad(new IllegalStateException("버그"));

        // when
        saved();
        fileHas(withSize(20));
        saved();

        // then
        assertThat(reloader.current()).isEqualTo(withSize(20));
    }
}
