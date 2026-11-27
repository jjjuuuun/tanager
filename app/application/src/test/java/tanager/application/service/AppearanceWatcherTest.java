package tanager.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.in.ResolvedAppearance;
import tanager.application.port.out.AnimationMode;
import tanager.application.port.out.AppConfig;
import tanager.application.port.out.AppearanceConfig;
import tanager.application.port.out.DirectUiDispatcher;
import tanager.application.port.out.FakePlatformModule;
import tanager.application.port.out.FakeScheduler;
import tanager.application.port.out.LoadedConfig;
import tanager.application.port.out.QueuedUiDispatcher;
import tanager.application.port.out.Subscription;
import tanager.application.port.out.SystemAppearance;
import tanager.application.port.out.TerminalConfig;
import tanager.application.port.out.Theme;
import tanager.application.port.out.ThemeMode;
import tanager.application.state.AppState;
import tanager.application.state.ManualExecutor;
import tanager.application.state.SettingsLoaded;
import tanager.application.state.StateActor;
import tanager.domain.history.HistoryPolicy;
import tanager.domain.history.WorkHistory;

/** OS 외관을 주기 · 창 활성화 때 다시 읽고 설정과 합쳐 바뀔 때만 알린다(FR-UI-01, ADR-0018). */
class AppearanceWatcherTest {
    private static final SystemAppearance OS_DARK = new SystemAppearance(Optional.of(Theme.DARK), true);
    private static final SystemAppearance OS_LIGHT = new SystemAppearance(Optional.of(Theme.LIGHT), true);

    private final ManualExecutor actor = new ManualExecutor();
    private final StateActor state = new StateActor(AppState.initial(WorkHistory.empty(HistoryPolicy.DEFAULT)), actor);
    private final FakePlatformModule platform = new FakePlatformModule(List.of()).withAppearance(OS_DARK);
    private final FakeScheduler scheduler = new FakeScheduler();
    private final ManualExecutor io = new ManualExecutor();
    private final DirectUiDispatcher ui = new DirectUiDispatcher();
    private final List<ResolvedAppearance> received = new CopyOnWriteArrayList<>();

    private AppearanceWatcher watcher() {
        return new AppearanceWatcher(state, platform, scheduler, io, ui);
    }

    private static AppConfig withAppearance(ThemeMode theme, AnimationMode animations) {
        AppConfig base = AppConfig.defaults();
        return new AppConfig(
                base.defaultProfile(),
                base.profiles(),
                base.openPlacement(),
                new AppearanceConfig(theme, animations),
                base.terminal(),
                base.history(),
                base.monitor(),
                base.keys());
    }

    private static AppConfig withFontSize(AppConfig config, int size) {
        return new AppConfig(
                config.defaultProfile(),
                config.profiles(),
                config.openPlacement(),
                config.appearance(),
                new TerminalConfig("D2Coding", size, 1.2, 10_000),
                config.history(),
                config.monitor(),
                config.keys());
    }

    private void settingsBecome(AppConfig config) {
        state.submit(new SettingsLoaded(new LoadedConfig(config, List.of())));
        actor.runAll();
    }

    /** 한 주기만큼 시간을 보내고 그 주기의 읽기를 돌린다. */
    private void nextPeriod() {
        scheduler.advance(AppearanceWatcher.INTERVAL);
        io.runAll();
    }

    @Test
    @DisplayName("읽기 전 current() 는 기본 설정과 OS 기본값을 합친 다크 · 움직임 켬이고 OS 를 읽지 않는다")
    void currentBeforeReadingIsDefault() {
        // given
        platform.withAppearance(OS_LIGHT);

        // when
        ResolvedAppearance current = watcher().current();

        // then
        assertThat(current).isEqualTo(new ResolvedAppearance(Theme.DARK, true));
        assertThat(platform.appearanceCalls()).isZero();
    }

    @Test
    @DisplayName("readNow() 는 부른 스레드에서 OS 를 한 번 읽어 current() 에 반영한다")
    void readNowReadsOnCallingThread() {
        // given
        platform.withAppearance(new SystemAppearance(Optional.of(Theme.LIGHT), false));
        AppearanceWatcher watcher = watcher();

        // when
        ResolvedAppearance read = watcher.readNow();

        // then
        assertThat(read).isEqualTo(new ResolvedAppearance(Theme.LIGHT, false));
        assertThat(watcher.current()).isEqualTo(read);
        assertThat(platform.appearanceCalls()).isEqualTo(1);
        assertThat(io.pending()).isZero();
    }

    @Test
    @DisplayName("이미 읽은 설정이 있으면 그 설정으로 시작한다")
    void startsFromLoadedSettings() {
        // given
        settingsBecome(withAppearance(ThemeMode.LIGHT, AnimationMode.OFF));

        // when
        AppearanceWatcher watcher = watcher();

        // then
        assertThat(watcher.current()).isEqualTo(new ResolvedAppearance(Theme.LIGHT, false));
    }

    @Test
    @DisplayName("구독 중 다음 주기에 OS 가 라이트로 바뀌면 리스너가 한 번 불린다")
    void periodicReadNotifiesOnceWhenOsChanges() {
        // given
        AppearanceWatcher watcher = watcher();
        watcher.readNow();
        watcher.subscribe(received::add);

        // when
        platform.withAppearance(OS_LIGHT);
        nextPeriod();

        // then
        assertThat(received).containsExactly(new ResolvedAppearance(Theme.LIGHT, true));
        assertThat(watcher.current()).isEqualTo(new ResolvedAppearance(Theme.LIGHT, true));
    }

    @Test
    @DisplayName("OS 값이 같으면 주기마다 읽어도 알림이 없다")
    void sameValueDoesNotNotify() {
        // given
        AppearanceWatcher watcher = watcher();
        watcher.readNow();
        watcher.subscribe(received::add);

        // when
        nextPeriod();
        nextPeriod();

        // then
        assertThat(platform.appearanceCalls()).isEqualTo(3);
        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("주기는 5초다 — 5초 전에는 읽지 않는다")
    void periodIsFiveSeconds() {
        // given
        AppearanceWatcher watcher = watcher();
        watcher.subscribe(received::add);

        // when
        scheduler.advance(Duration.ofMillis(4_999));
        io.runAll();

        // then
        assertThat(platform.appearanceCalls()).isZero();
        assertThat(scheduler.untilNext()).contains(Duration.ofMillis(1));
    }

    @Test
    @DisplayName("설정 theme 이 DARK 로 바뀌면 OS 값과 상관없이 DARK 로 한 번 알린다")
    void settingsChangeNotifiesOnce() {
        // given
        platform.withAppearance(OS_LIGHT);
        AppearanceWatcher watcher = watcher();
        watcher.readNow();
        watcher.subscribe(received::add);

        // when
        AppConfig dark = withAppearance(ThemeMode.DARK, AnimationMode.AUTO);
        settingsBecome(dark);
        settingsBecome(withFontSize(dark, 16));
        nextPeriod();

        // then
        assertThat(received).containsExactly(new ResolvedAppearance(Theme.DARK, true));
    }

    @Test
    @DisplayName("설정이 animations OFF 로 바뀌면 움직임 끔을 알린다")
    void animationSettingNotifies() {
        // given
        AppearanceWatcher watcher = watcher();
        watcher.readNow();
        watcher.subscribe(received::add);

        // when
        settingsBecome(withAppearance(ThemeMode.SYSTEM, AnimationMode.OFF));

        // then
        assertThat(received).containsExactly(new ResolvedAppearance(Theme.DARK, false));
    }

    @Test
    @DisplayName("refresh() 는 I/O 실행기에서 다시 읽고 바뀌었으면 알린다")
    void refreshReadsOnIoExecutor() {
        // given
        AppearanceWatcher watcher = watcher();
        watcher.readNow();
        watcher.subscribe(received::add);
        platform.withAppearance(OS_LIGHT);

        // when
        watcher.refresh();

        // then — 부른 스레드에서는 읽지 않는다
        assertThat(platform.appearanceCalls()).isEqualTo(1);
        io.runAll();
        assertThat(platform.appearanceCalls()).isEqualTo(2);
        assertThat(received).containsExactly(new ResolvedAppearance(Theme.LIGHT, true));
    }

    @Test
    @DisplayName("0.1초 안에 refresh() 를 10번 불러도 읽기는 하나만 예약된다")
    void refreshBurstQueuesOneRead() {
        // given
        AppearanceWatcher watcher = watcher();

        // when
        for (int i = 0; i < 10; i++) {
            watcher.refresh();
        }

        // then
        assertThat(io.pending()).isEqualTo(1);
        io.runAll();
        assertThat(platform.appearanceCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("읽는 도중 refresh() 가 몰려 와도 appearance() 는 동시에 하나만 돌고, 끝난 뒤 한 번만 더 읽는다")
    void refreshDuringReadRunsOneAtATime() {
        // given — 실제 가상 스레드로 읽고 첫 읽기를 막아 둔다
        CountDownLatch release = new CountDownLatch(1);
        platform.blockAppearanceUntil(release);
        Executor virtual = task -> Thread.ofVirtual().start(task);
        AppearanceWatcher watcher = new AppearanceWatcher(state, platform, scheduler, virtual, ui);
        watcher.refresh();
        await().atMost(Duration.ofSeconds(5)).until(() -> platform.appearanceCalls() == 1);

        // when
        for (int i = 0; i < 9; i++) {
            watcher.refresh();
        }
        release.countDown();

        // then
        await().atMost(Duration.ofSeconds(5)).until(() -> platform.appearanceCalls() == 2);
        assertThat(platform.maxConcurrentAppearanceCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("구독을 끊으면 주기 읽기가 멈추고 더 알리지 않는다")
    void cancelStopsPolling() {
        // given
        AppearanceWatcher watcher = watcher();
        watcher.readNow();
        Subscription subscription = watcher.subscribe(received::add);

        // when
        subscription.cancel();
        platform.withAppearance(OS_LIGHT);
        scheduler.advance(Duration.ofSeconds(20));
        io.runAll();

        // then
        assertThat(scheduler.pendingCount()).isZero();
        assertThat(platform.appearanceCalls()).isEqualTo(1);
        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("구독이 없으면 주기 읽기를 예약하지 않고, 다시 구독하면 주기가 다시 돈다")
    void pollsOnlyWhileSubscribed() {
        // given
        AppearanceWatcher watcher = watcher();
        assertThat(scheduler.pendingCount()).isZero();
        watcher.subscribe(received::add).cancel();

        // when
        watcher.subscribe(received::add);
        platform.withAppearance(OS_LIGHT);
        nextPeriod();

        // then
        assertThat(platform.appearanceCalls()).isEqualTo(1);
        assertThat(received).containsExactly(new ResolvedAppearance(Theme.LIGHT, true));
        assertThat(scheduler.pendingCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("구독이 둘이면 하나를 끊어도 주기는 계속 돈다")
    void pollingContinuesWhileAnySubscriber() {
        // given
        AppearanceWatcher watcher = watcher();
        List<ResolvedAppearance> other = new CopyOnWriteArrayList<>();
        Subscription first = watcher.subscribe(other::add);
        watcher.subscribe(received::add);

        // when
        first.cancel();
        platform.withAppearance(OS_LIGHT);
        nextPeriod();

        // then
        assertThat(received).containsExactly(new ResolvedAppearance(Theme.LIGHT, true));
        assertThat(other).isEmpty();
    }

    @Test
    @DisplayName("알림은 UiDispatcher 를 거치고, UI 에서 돌기 전에 끊은 구독은 받지 않는다")
    void deliversThroughUiDispatcher() {
        // given
        QueuedUiDispatcher queued = new QueuedUiDispatcher();
        AppearanceWatcher watcher = new AppearanceWatcher(state, platform, scheduler, io, queued);
        watcher.readNow();
        List<ResolvedAppearance> cancelled = new CopyOnWriteArrayList<>();
        Subscription early = watcher.subscribe(cancelled::add);
        watcher.subscribe(received::add);
        platform.withAppearance(OS_LIGHT);
        watcher.refresh();
        io.runAll();

        // when
        assertThat(received).isEmpty();
        early.cancel();
        queued.runAll();

        // then
        assertThat(received).containsExactly(new ResolvedAppearance(Theme.LIGHT, true));
        assertThat(cancelled).isEmpty();
    }

    @Test
    @DisplayName("리스너가 던져도 다른 리스너는 받고 다음 알림도 간다")
    void throwingListenerDoesNotBreakOthers() {
        // given
        AppearanceWatcher watcher = watcher();
        watcher.readNow();
        watcher.subscribe(value -> {
            throw new IllegalStateException("UI 버그");
        });
        watcher.subscribe(received::add);

        // when
        platform.withAppearance(OS_LIGHT);
        watcher.refresh();
        io.runAll();
        platform.withAppearance(OS_DARK);
        watcher.refresh();
        io.runAll();

        // then
        assertThat(received)
                .containsExactly(new ResolvedAppearance(Theme.LIGHT, true), new ResolvedAppearance(Theme.DARK, true));
    }

    @Test
    @DisplayName("appearance() 가 던지면 마지막 값을 그대로 두고 다음 읽기는 다시 된다")
    void failingReadKeepsLastValue() {
        // given
        AppearanceWatcher watcher = watcher();
        watcher.readNow();
        watcher.subscribe(received::add);
        platform.failAppearanceWith(Optional.of(new IllegalStateException("계약 위반")));

        // when
        watcher.refresh();
        io.runAll();
        platform.failAppearanceWith(Optional.empty()).withAppearance(OS_LIGHT);
        watcher.refresh();
        io.runAll();

        // then
        assertThat(platform.appearanceCalls()).isEqualTo(3);
        assertThat(received).containsExactly(new ResolvedAppearance(Theme.LIGHT, true));
    }

    @Test
    @DisplayName("OS 테마를 읽지 못하면(empty) 시스템 모드는 다크다")
    void unreadableOsThemeIsDark() {
        // given
        platform.withAppearance(OS_LIGHT);
        AppearanceWatcher watcher = watcher();
        watcher.readNow();
        watcher.subscribe(received::add);

        // when
        platform.withAppearance(SystemAppearance.DEFAULT);
        nextPeriod();

        // then
        assertThat(received).containsExactly(new ResolvedAppearance(Theme.DARK, true));
    }
}
