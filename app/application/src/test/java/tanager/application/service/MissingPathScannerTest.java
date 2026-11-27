package tanager.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.in.AppEvent;
import tanager.application.port.out.DirectoryProbe;
import tanager.application.port.out.FakeDirectoryProbe;
import tanager.application.port.out.LoadedHistory;
import tanager.application.port.out.StoreAccess;
import tanager.application.state.AppState;
import tanager.application.state.FakeClock;
import tanager.application.state.HistoryLoaded;
import tanager.application.state.ManualExecutor;
import tanager.application.state.StateActor;
import tanager.application.state.Transition;
import tanager.domain.history.HistoryPolicy;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkHistory;
import tanager.domain.history.WorkspacePath;
import tanager.domain.session.IntegrationMode;
import tanager.domain.session.LaunchTemplate;
import tanager.domain.session.LiveSessions;
import tanager.domain.session.ProfileId;
import tanager.domain.session.ShellKind;
import tanager.domain.session.ShellProfile;
import tanager.domain.session.TerminalSession;
import tanager.domain.session.WorkingDir;
import tanager.domain.shared.PaneId;

/** 사라진 경로를 백그라운드로 확인한다(FR-HIS-08, #186). 시간은 {@link FakeClock}, 실행은 {@link ManualExecutor} 로 결정적으로. */
class MissingPathScannerTest {
    private static final ShellEnvironment WIN = new ShellEnvironment.Windows();
    private static final ShellEnvironment UBUNTU = new ShellEnvironment.Wsl("Ubuntu");
    private static final ShellProfile UBUNTU_BASH = new ShellProfile(
            new ProfileId("wsl:Ubuntu"),
            "Ubuntu",
            ShellKind.BASH,
            UBUNTU,
            new LaunchTemplate("wsl.exe", List.of()),
            IntegrationMode.OSC7);
    private static final WorkspacePath GONE = path(WIN, "C:\\gone");
    private static final WorkspacePath HERE = path(WIN, "C:\\here");
    private static final WorkspacePath OLD = path(UBUNTU, "/home/me/old");
    private static final Instant T0 = Instant.parse("2026-11-28T00:00:00Z");

    private final ManualExecutor actorExecutor = new ManualExecutor();
    private final ManualExecutor io = new ManualExecutor();
    private final FakeClock clock = new FakeClock(T0);
    private final FakeDirectoryProbe probe = new FakeDirectoryProbe();
    private final List<AppEvent> events = Collections.synchronizedList(new ArrayList<>());
    private final StateActor actor =
            new StateActor(AppState.initial(WorkHistory.empty(HistoryPolicy.DEFAULT)), actorExecutor);

    private static WorkspacePath path(ShellEnvironment env, String raw) {
        return WorkspacePath.of(env, raw).value().orElseThrow();
    }

    private MissingPathScanner scanner(DirectoryProbe directories, Executor executor) {
        actor.subscribe(events::add);
        return new MissingPathScanner(actor, directories, executor, clock);
    }

    private MissingPathScanner scanner() {
        return scanner(probe, io);
    }

    private void settle() {
        while (actorExecutor.pending() > 0 || io.pending() > 0) {
            actorExecutor.runAll();
            io.runAll();
        }
    }

    private void load(WorkspacePath... visited) {
        WorkHistory h = WorkHistory.empty(HistoryPolicy.DEFAULT);
        for (WorkspacePath p : visited) {
            h = h.recordVisit(p, T0);
        }
        actor.submit(new HistoryLoaded(new LoadedHistory(h, StoreAccess.WRITABLE, List.of())));
        settle();
    }

    private void visit(WorkspacePath p) {
        actor.submit(current -> {
            WorkHistory next = current.history().recordVisit(p, clock.instant());
            return new Transition<>(current.withHistory(next), List.of(new AppEvent.HistoryChanged(next)), true);
        });
        settle();
    }

    private void ubuntuSessionStarted() {
        TerminalSession running = TerminalSession.start(UBUNTU_BASH, new WorkingDir.Home())
                .value()
                .orElseThrow()
                .launched(10)
                .value()
                .orElseThrow();
        actor.submit(current -> {
            LiveSessions next = current.sessions().launched(new PaneId("p1"), running);
            return new Transition<>(current.withSessions(next), List.of(new AppEvent.SessionsChanged(next)), true);
        });
        settle();
    }

    private List<WorkspacePath> probed() {
        return probe.calls().stream().map(FakeDirectoryProbe.Call::path).toList();
    }

    @Test
    @DisplayName("불러온 이력을 한 번 확인해 없는 Windows 경로만 회색으로 하고, 세션 없는 Ubuntu 경로는 확인하지 않는다")
    void scansWindowsButNotSleepingWsl() {
        // given
        probe.missing(GONE, OLD);
        scanner();
        // when
        load(GONE, HERE, OLD);
        // then
        assertThat(actor.snapshot().missing()).containsExactly(GONE);
        assertThat(probed()).containsExactlyInAnyOrder(GONE, HERE).doesNotContain(OLD);
        assertThat(events).contains(new AppEvent.MissingPathsChanged(Set.of(GONE)));
    }

    @Test
    @DisplayName("항목마다 500ms 시간 제한으로 확인한다")
    void timeoutPerEntry() {
        // given
        scanner();
        // when
        load(HERE);
        // then
        assertThat(probe.calls()).extracting(FakeDirectoryProbe.Call::timeout).containsExactly(Duration.ofMillis(500));
    }

    @Test
    @DisplayName("Ubuntu 세션이 뜨면 다음 스캔에서 그 배포판 경로를 확인한다")
    void wakesWslWhenSessionRuns() {
        // given
        probe.missing(OLD);
        scanner();
        load(OLD);
        assertThat(probed()).isEmpty();
        // when
        ubuntuSessionStarted();
        // then
        assertThat(probed()).containsExactly(OLD);
        assertThat(actor.snapshot().missing()).containsExactly(OLD);
    }

    @Test
    @DisplayName("확인 결과를 모르면(시간 초과) 회색으로 하지 않는다")
    void unknownIsNotMissing() {
        // given
        probe.unknown(GONE);
        scanner();
        // when
        load(GONE);
        // then
        assertThat(probed()).containsExactly(GONE);
        assertThat(actor.snapshot().missing()).isEmpty();
        assertThat(events).noneMatch(AppEvent.MissingPathsChanged.class::isInstance);
    }

    @Test
    @DisplayName("이력이 바뀌면 새 항목만 확인한다")
    void historyChangeChecksOnlyNew() {
        // given
        scanner();
        load(HERE);
        // when
        visit(HERE);
        visit(GONE);
        // then
        assertThat(probed()).containsExactly(HERE, GONE);
    }

    @Test
    @DisplayName("폴더를 다시 만들면 5분 뒤 창 활성화 때 회색이 풀린다 — 5분 전에는 확인하지 않는다")
    void windowActivationRechecksStale() {
        // given
        probe.missing(GONE);
        MissingPathScanner scanner = scanner();
        load(GONE);
        assertThat(actor.snapshot().missing()).containsExactly(GONE);
        probe.exists(GONE);
        // when
        clock.advance(Duration.ofMinutes(4));
        scanner.windowActivated();
        settle();
        // then
        assertThat(probed()).containsExactly(GONE);
        assertThat(actor.snapshot().missing()).containsExactly(GONE);
        // when
        clock.advance(Duration.ofMinutes(1));
        scanner.windowActivated();
        settle();
        // then
        assertThat(probed()).containsExactly(GONE, GONE);
        assertThat(actor.snapshot().missing()).isEmpty();
        assertThat(events).contains(new AppEvent.MissingPathsChanged(Set.of()));
    }

    @Test
    @DisplayName("확인 중인 경로는 다시 맡기지 않는다")
    void inFlightNotDuplicated() {
        // given
        scanner();
        actor.submit(new HistoryLoaded(new LoadedHistory(
                WorkHistory.empty(HistoryPolicy.DEFAULT).recordVisit(HERE, T0), StoreAccess.WRITABLE, List.of())));
        actorExecutor.runAll();
        // when — 확인이 끝나기 전에 같은 항목으로 이력이 또 바뀐다
        visit(HERE);
        // then
        assertThat(probed()).containsExactly(HERE);
    }

    @Test
    @DisplayName("확인하는 동안 기록이 지워진 경로는 회색 집합에 넣지 않는다")
    void forgottenWhileCheckingIgnored() {
        // given
        probe.missing(GONE);
        scanner();
        actor.submit(new HistoryLoaded(new LoadedHistory(
                WorkHistory.empty(HistoryPolicy.DEFAULT).recordVisit(GONE, T0), StoreAccess.WRITABLE, List.of())));
        actorExecutor.runAll();
        actor.submit(current -> {
            WorkHistory next = current.history().forget(GONE).value().orElseThrow();
            return new Transition<>(current.withHistory(next), List.of(new AppEvent.HistoryChanged(next)), true);
        });
        actorExecutor.runAll();
        // when
        settle();
        // then
        assertThat(actor.snapshot().missing()).isEmpty();
    }

    @Test
    @DisplayName("확인기가 던지면 모름으로 본다")
    void throwingProbeIsUnknown() {
        // given
        scanner(
                (p, timeout) -> {
                    throw new IllegalStateException("boom");
                },
                io);
        // when
        load(GONE);
        // then
        assertThat(actor.snapshot().missing()).isEmpty();
    }

    @Test
    @DisplayName("동시에 4개까지만 확인한다")
    void atMostFourAtOnce() throws InterruptedException {
        // given
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicInteger done = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        DirectoryProbe blocking = (p, timeout) -> {
            peak.accumulateAndGet(inside.incrementAndGet(), Math::max);
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inside.decrementAndGet();
            done.incrementAndGet();
            return Optional.of(true);
        };
        scanner(blocking, task -> Thread.ofVirtual().start(task));
        List<WorkspacePath> six = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            six.add(path(WIN, "C:\\d" + i));
        }
        // when
        load(six.toArray(WorkspacePath[]::new));
        // then
        await().atMost(Duration.ofSeconds(5)).until(() -> inside.get() == 4);
        assertThat(done.get()).isZero();
        release.countDown();
        await().atMost(Duration.ofSeconds(5)).until(() -> done.get() == 6);
        assertThat(peak.get()).isEqualTo(4);
    }
}
