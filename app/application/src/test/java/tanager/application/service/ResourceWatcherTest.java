package tanager.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.in.AppEvent;
import tanager.application.port.in.ResourceReport;
import tanager.application.port.out.AppConfig;
import tanager.application.port.out.FakeResourceProbe;
import tanager.application.port.out.FakeScheduler;
import tanager.application.port.out.MonitorConfig;
import tanager.application.port.out.MonitorItem;
import tanager.application.port.out.ProbeError;
import tanager.application.port.out.QueuedUiDispatcher;
import tanager.application.port.out.RecordingSystemTools;
import tanager.application.port.out.Subscription;
import tanager.application.state.AppState;
import tanager.application.state.FakeClock;
import tanager.application.state.StateActor;
import tanager.application.state.Transition;
import tanager.domain.history.HistoryPolicy;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkHistory;
import tanager.domain.history.WorkspacePath;
import tanager.domain.monitor.HostUsage;
import tanager.domain.monitor.UsageLevel;
import tanager.domain.session.IntegrationMode;
import tanager.domain.session.LaunchTemplate;
import tanager.domain.session.LiveSessions;
import tanager.domain.session.ProfileId;
import tanager.domain.session.SessionState;
import tanager.domain.session.ShellKind;
import tanager.domain.session.ShellProfile;
import tanager.domain.session.TerminalSession;
import tanager.domain.session.WorkingDir;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.Result;

/** 리소스 수집 주기 · WSL 호출 제한 · 설정 반영(FR-MON-01~04, NFR-10, R9, #187). */
class ResourceWatcherTest {
    private static final PaneId P1 = new PaneId("p1");
    private static final ShellEnvironment UBUNTU = new ShellEnvironment.Wsl("Ubuntu");
    private static final ShellProfile UBUNTU_BASH = profile("ubuntu", ShellKind.BASH, UBUNTU, IntegrationMode.OSC7);
    private static final ShellProfile POWERSHELL =
            profile("powershell", ShellKind.WINDOWS_POWERSHELL, new ShellEnvironment.Windows(), IntegrationMode.OSC9_9);

    private final StateActor state =
            new StateActor(AppState.initial(WorkHistory.empty(HistoryPolicy.DEFAULT)), Runnable::run);
    private final FakeResourceProbe probe = new FakeResourceProbe();
    private final RecordingSystemTools tools = new RecordingSystemTools();
    private final FakeScheduler scheduler = new FakeScheduler();
    private final FakeClock clock = FakeClock.atEpoch();
    private final QueuedUiDispatcher ui = new QueuedUiDispatcher();
    private final List<ResourceReport> reports = new ArrayList<>();
    private final ResourceWatcher watcher =
            new ResourceWatcher(state, probe, tools, scheduler, Runnable::run, clock, ui);

    private static ShellProfile profile(String id, ShellKind kind, ShellEnvironment env, IntegrationMode mode) {
        return new ShellProfile(new ProfileId(id), id, kind, env, new LaunchTemplate(id + ".exe", List.of()), mode);
    }

    private static WorkspacePath path(ShellEnvironment env, String raw) {
        return WorkspacePath.of(env, raw).value().orElseThrow();
    }

    // 포커스 창 p1 에 profile 셸이 cwd 에서 돈다
    private void focusedRunning(ShellProfile profile, WorkspacePath cwd) {
        TerminalSession running = new TerminalSession(profile, new SessionState.Running(42, new WorkingDir.At(cwd)));
        setSessions(LiveSessions.empty().launched(P1, running));
    }

    private void setSessions(LiveSessions sessions) {
        state.submit(current -> new Transition<>(current.withSessions(sessions), List.of(), true));
    }

    private void settingsChanged(UnaryOperator<MonitorConfig> change) {
        AppConfig base = state.snapshot().settings().orElse(AppConfig.defaults());
        MonitorConfig monitor = change.apply(base.monitor());
        AppConfig next = new AppConfig(
                base.defaultProfile(),
                base.profiles(),
                base.openPlacement(),
                base.appearance(),
                base.terminal(),
                base.history(),
                monitor,
                base.keys());
        state.submit(current ->
                new Transition<>(current.withSettings(next), List.of(new AppEvent.SettingsChanged(next)), true));
    }

    private static MonitorConfig withInterval(MonitorConfig m, int seconds) {
        return new MonitorConfig(m.enabled(), seconds, m.items(), m.cpu(), m.cpuSustainSec(), m.memory(), m.disk());
    }

    private static MonitorConfig disabled(MonitorConfig m) {
        return new MonitorConfig(false, m.intervalSec(), m.items(), m.cpu(), m.cpuSustainSec(), m.memory(), m.disk());
    }

    private Subscription subscribe() {
        Subscription subscription = watcher.subscribe(reports::add);
        tick(Duration.ZERO);
        return subscription;
    }

    // 시계와 스케줄러를 1초씩 함께 진행하고 UI 큐를 비운다
    private void tick(Duration amount) {
        scheduler.advance(Duration.ZERO);
        for (long s = 0; s < amount.toSeconds(); s++) {
            clock.advance(Duration.ofSeconds(1));
            scheduler.advance(Duration.ofSeconds(1));
        }
        ui.runAll();
    }

    private ResourceReport last() {
        return reports.getLast();
    }

    @Test
    @DisplayName("구독하면 바로 한 번 재고, 기본 주기 2초마다 다시 재서 UI 스레드로 보낸다")
    void samplesImmediatelyThenEveryInterval() {
        // when
        watcher.subscribe(reports::add);
        scheduler.advance(Duration.ZERO);

        // then: UI 디스패처를 거친다
        assertThat(reports).isEmpty();
        ui.runAll();
        assertThat(reports).hasSize(1);
        assertThat(probe.hostCalls()).isEqualTo(1);

        // when
        tick(Duration.ofSeconds(10));

        // then: 0 · 2 · 4 · 6 · 8 · 10
        assertThat(reports).hasSize(6);
        assertThat(last().sample().at()).isEqualTo(clock.instant());
        assertThat(last().items()).isEqualTo(EnumSet.allOf(MonitorItem.class));
    }

    @Test
    @DisplayName("실행 중인 WSL 세션이 없으면 10주기 동안 wsl() 을 한 번도 부르지 않는다")
    void neverWakesWslWithoutRunningSession() {
        // given: 포커스 창은 PowerShell
        focusedRunning(POWERSHELL, path(new ShellEnvironment.Windows(), "C:\\work"));
        subscribe();

        // when
        tick(Duration.ofSeconds(20));

        // then
        assertThat(probe.hostCalls()).isEqualTo(11);
        assertThat(probe.wslCalls()).isEmpty();
        assertThat(last().sample().wsl()).isEmpty();
        assertThat(last().wslUnavailable()).isEmpty();
    }

    @Test
    @DisplayName("포커스 창이 WSL 이어도 그 세션이 끝났고 그 배포판에 도는 세션이 없으면 wsl() 을 부르지 않는다")
    void exitedWslSessionDoesNotCount() {
        // given
        focusedRunning(UBUNTU_BASH, path(UBUNTU, "/home/u"));
        setSessions(state.snapshot().sessions().exited(P1, 0));
        subscribe();

        // when
        tick(Duration.ofSeconds(20));

        // then
        assertThat(probe.wslCalls()).isEmpty();
    }

    @Test
    @DisplayName("포커스 창이 Ubuntu(Running) · 주기 2초면 10초 동안 wsl() 은 2번 이하이고, 그 사이 표본은 마지막 값을 쓴다")
    void wslAtMostEveryFiveSeconds() {
        // given
        focusedRunning(UBUNTU_BASH, path(UBUNTU, "/home/u"));
        subscribe();

        // when
        tick(Duration.ofSeconds(10));

        // then
        assertThat(probe.wslCalls()).hasSizeBetween(1, 2).containsOnly("Ubuntu");
        assertThat(probe.wslTimeouts()).containsOnly(Duration.ofSeconds(1));
        assertThat(reports).allSatisfy(r -> assertThat(r.sample().wsl()).isPresent());
        assertThat(last().levels().wslMemory()).contains(UsageLevel.NORMAL);
    }

    @Test
    @DisplayName("wsl() 이 시간 초과면 report 의 wsl 은 비고 이유가 있으며, 다음 시도는 5초 뒤다")
    void timeoutReportsReasonAndRetriesAfterFiveSeconds() {
        // given: 주기 1초
        settingsChanged(m -> withInterval(m, 1));
        focusedRunning(UBUNTU_BASH, path(UBUNTU, "/home/u"));
        ProbeError timedOut = new ProbeError.TimedOut(Duration.ofSeconds(1));
        probe.withWsl(Result.err(timedOut));
        subscribe();

        // then
        assertThat(last().sample().wsl()).isEmpty();
        assertThat(last().wslUnavailable()).contains(timedOut);

        // when: 4초 — 아직 다시 부르지 않고 이유를 계속 보인다
        tick(Duration.ofSeconds(4));

        // then
        assertThat(probe.wslCalls()).hasSize(1);
        assertThat(last().wslUnavailable()).contains(timedOut);

        // when: 5초
        tick(Duration.ofSeconds(1));

        // then
        assertThat(probe.wslCalls()).hasSize(2);
    }

    @Test
    @DisplayName("구독을 해제하면 probe 를 더 부르지 않는다")
    void unsubscribeStopsSampling() {
        // given
        Subscription subscription = subscribe();
        int before = probe.totalCalls();

        // when
        subscription.cancel();
        tick(Duration.ofSeconds(10));

        // then
        assertThat(probe.totalCalls()).isEqualTo(before);
        assertThat(scheduler.pendingCount()).isZero();
    }

    @Test
    @DisplayName("구독이 없으면 수집하지 않는다")
    void noSubscriberNoSampling() {
        // when
        tick(Duration.ofSeconds(10));

        // then
        assertThat(probe.totalCalls()).isZero();
    }

    @Test
    @DisplayName("monitor.enabled=false 면 probe 를 부르지 않고, 다시 켜면 바로 잰다")
    void disabledStopsSampling() {
        // given
        settingsChanged(ResourceWatcherTest::disabled);

        // when
        subscribe();
        tick(Duration.ofSeconds(10));

        // then
        assertThat(probe.totalCalls()).isZero();
        assertThat(reports).isEmpty();

        // when
        settingsChanged(m ->
                new MonitorConfig(true, m.intervalSec(), m.items(), m.cpu(), m.cpuSustainSec(), m.memory(), m.disk()));
        tick(Duration.ZERO);

        // then
        assertThat(probe.hostCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("setItemVisible(DISK, false) 면 disk() 를 부르지 않고 표본에도 디스크가 없다")
    void hiddenDiskIsNotCollected() {
        // given
        focusedRunning(POWERSHELL, path(new ShellEnvironment.Windows(), "C:\\work"));
        subscribe();
        assertThat(probe.diskCalls()).hasSize(1);

        // when
        watcher.setItemVisible(MonitorItem.DISK, false);
        tick(Duration.ofSeconds(4));

        // then
        assertThat(probe.diskCalls()).hasSize(1);
        assertThat(last().sample().disk()).isEmpty();
        assertThat(last().items()).doesNotContain(MonitorItem.DISK);

        // when
        watcher.setItemVisible(MonitorItem.DISK, true);
        tick(Duration.ofSeconds(2));

        // then
        assertThat(probe.diskCalls()).hasSize(2);
    }

    @Test
    @DisplayName("setItemVisible(WSL, false) 면 WSL 세션이 있어도 wsl() 을 부르지 않는다")
    void hiddenWslIsNotCollected() {
        // given
        focusedRunning(UBUNTU_BASH, path(UBUNTU, "/home/u"));
        watcher.setItemVisible(MonitorItem.WSL, false);

        // when
        subscribe();
        tick(Duration.ofSeconds(10));

        // then
        assertThat(probe.wslCalls()).isEmpty();
    }

    @Test
    @DisplayName("모든 항목을 끄면 수집을 멈추고, 하나를 켜면 다시 잰다")
    void allHiddenStopsSampling() {
        // given
        subscribe();
        int before = probe.hostCalls();

        // when
        for (MonitorItem item : MonitorItem.values()) {
            watcher.setItemVisible(item, false);
        }
        tick(Duration.ofSeconds(10));

        // then
        assertThat(probe.hostCalls()).isEqualTo(before);

        // when
        watcher.setItemVisible(MonitorItem.CPU, true);
        tick(Duration.ZERO);

        // then
        assertThat(probe.hostCalls()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("포커스 창 cwd 를 disk() 에 넘긴다 — WSL /mnt/d/x 도 그대로 넘기고 바꾸는 일은 probe 가 한다")
    void passesFocusedCwdToDisk() {
        // given
        WorkspacePath mounted = path(UBUNTU, "/mnt/d/x");
        focusedRunning(UBUNTU_BASH, mounted);

        // when
        subscribe();

        // then
        assertThat(probe.diskCalls()).containsExactly(mounted);
    }

    @Test
    @DisplayName("포커스 창 세션을 모르면(아직 안 뜸) 디스크를 재지 않는다")
    void unknownCwdHasNoDisk() {
        // when
        subscribe();

        // then
        assertThat(probe.diskCalls()).isEmpty();
        assertThat(last().sample().disk()).isEmpty();
    }

    @Test
    @DisplayName("SettingsChanged(intervalSec 5) 면 다음 주기부터 5초다")
    void intervalChangeAppliesFromNextPeriod() {
        // given
        subscribe();
        tick(Duration.ofSeconds(1));

        // when: t=1 에 바꾼다. t=2 에 예약된 주기는 그대로 돈다
        settingsChanged(m -> withInterval(m, 5));
        tick(Duration.ofSeconds(1));

        // then
        assertThat(probe.hostCalls()).isEqualTo(2);
        assertThat(scheduler.untilNext()).contains(Duration.ofSeconds(5));

        // when
        tick(Duration.ofSeconds(4));

        // then
        assertThat(probe.hostCalls()).isEqualTo(2);
        tick(Duration.ofSeconds(1));
        assertThat(probe.hostCalls()).isEqualTo(3);
    }

    @Test
    @DisplayName("설정 임계값으로 판정한다 — CPU 가 지속 시간 동안 임계값 이상이면 WARN")
    void usesConfiguredThresholds() {
        // given: CPU 0.5 · 4초 지속, 주기 2초 → 2개 연속
        settingsChanged(m -> new MonitorConfig(true, 2, m.items(), 0.5, 4, 0.85, 0.90));
        probe.withHost(new HostUsage(0.6, 4, FakeResourceProbe.HALF));

        // when
        subscribe();

        // then
        assertThat(last().levels().cpu()).isEqualTo(UsageLevel.NORMAL);
        tick(Duration.ofSeconds(2));
        assertThat(last().levels().cpu()).isEqualTo(UsageLevel.WARN);
    }

    @Test
    @DisplayName("설정이 바뀌어도 monitor.items 가 같으면 상태줄에서 끈 항목은 그대로 꺼져 있다")
    void runtimeToggleSurvivesUnrelatedSettingsChange() {
        // given
        subscribe();
        watcher.setItemVisible(MonitorItem.DISK, false);

        // when
        settingsChanged(m -> withInterval(m, 3));
        tick(Duration.ofSeconds(2));

        // then
        assertThat(last().items()).doesNotContain(MonitorItem.DISK);

        // when: 설정의 항목이 바뀌면 그 값을 쓴다
        settingsChanged(m -> new MonitorConfig(
                true,
                3,
                EnumSet.of(MonitorItem.CPU, MonitorItem.DISK),
                m.cpu(),
                m.cpuSustainSec(),
                m.memory(),
                m.disk()));
        tick(Duration.ofSeconds(3));

        // then
        assertThat(last().items()).containsExactlyInAnyOrder(MonitorItem.CPU, MonitorItem.DISK);
    }

    @Test
    @DisplayName("host() 가 던져도 그 주기만 건너뛰고 다음 주기에 다시 잰다")
    void survivesProbeFailure() {
        // given
        probe.failHostWith(new IllegalStateException("고장"));

        // when
        subscribe();
        tick(Duration.ofSeconds(2));

        // then
        assertThat(reports).isEmpty();
        assertThat(probe.hostCalls()).isEqualTo(2);
    }

    @Test
    @DisplayName("openTool 은 그 항목의 OS 도구를 연다")
    void openToolDelegates() {
        // when
        watcher.openTool(MonitorItem.DISK);

        // then
        assertThat(tools.opened()).containsExactly(MonitorItem.DISK);
    }

    @Test
    @DisplayName("시작할 때 이미 읽은 설정이 있으면 그 설정을 쓴다")
    void usesSettingsAlreadyLoaded() {
        // given
        settingsChanged(ResourceWatcherTest::disabled);

        // when
        ResourceWatcher later = new ResourceWatcher(state, probe, tools, scheduler, Runnable::run, clock, ui);
        later.subscribe(reports::add);
        tick(Duration.ofSeconds(4));

        // then
        assertThat(probe.totalCalls()).isZero();
    }
}
