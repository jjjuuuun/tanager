package dev.tanager.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tanager.application.port.in.AppEvent;
import dev.tanager.application.state.AppState;
import dev.tanager.application.state.FakeClock;
import dev.tanager.application.state.ManualExecutor;
import dev.tanager.application.state.StateActor;
import dev.tanager.domain.history.HistoryPolicy;
import dev.tanager.domain.history.SessionLeaf;
import dev.tanager.domain.history.ShellEnvironment;
import dev.tanager.domain.history.WorkHistory;
import dev.tanager.domain.history.WorkspacePath;
import dev.tanager.domain.session.IntegrationMode;
import dev.tanager.domain.session.LaunchTemplate;
import dev.tanager.domain.session.LiveSessions;
import dev.tanager.domain.session.ProfileId;
import dev.tanager.domain.session.ShellKind;
import dev.tanager.domain.session.ShellProfile;
import dev.tanager.domain.session.TerminalSession;
import dev.tanager.domain.session.WorkingDir;
import dev.tanager.domain.shared.PaneId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecordVisitTest {
    private static final ShellEnvironment UBUNTU = new ShellEnvironment.Wsl("Ubuntu");
    private static final ShellProfile BASH = new ShellProfile(
            new ProfileId("wsl:Ubuntu"),
            "Ubuntu",
            ShellKind.BASH,
            UBUNTU,
            new LaunchTemplate("wsl.exe", List.of()),
            IntegrationMode.OSC7);
    private static final PaneId P1 = new PaneId("p1");
    private static final PaneId P2 = new PaneId("p2");
    private static final WorkspacePath A = wsl("/a");
    private static final WorkspacePath B = wsl("/b");
    private static final Instant T0 = Instant.parse("2026-11-27T00:00:00Z");

    private final ManualExecutor executor = new ManualExecutor();
    private final FakeClock clock = new FakeClock(T0);
    private final List<AppEvent> events = new ArrayList<>();
    private StateActor actor;
    private VisitRecorder visits;

    private static WorkspacePath wsl(String raw) {
        return WorkspacePath.of(UBUNTU, raw).value().orElseThrow();
    }

    private static TerminalSession running() {
        return TerminalSession.start(BASH, new WorkingDir.Home())
                .value()
                .orElseThrow()
                .launched(10)
                .value()
                .orElseThrow();
    }

    private void givenSessions(PaneId... panes) {
        LiveSessions sessions = LiveSessions.empty();
        for (PaneId pane : panes) {
            sessions = sessions.launched(pane, running());
        }
        AppState initial =
                AppState.initial(WorkHistory.empty(HistoryPolicy.DEFAULT)).withSessions(sessions);
        actor = new StateActor(initial, executor);
        actor.subscribe(events::add);
        visits = new VisitRecorder(actor, clock);
    }

    private void report(PaneId pane, WorkspacePath path) {
        visits.record(pane, path);
        executor.runAll();
    }

    private int count(WorkspacePath path) {
        return actor.snapshot().history().find(path).map(v -> v.visitCount()).orElse(0);
    }

    private SessionLeaf leaf(PaneId pane) {
        return actor.snapshot().sessions().leaves().stream()
                .filter(l -> l.pane().equals(pane))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("창이 처음 알린 경로를 이력에 기록하고 세션의 경로를 갱신해 두 이벤트를 낸다")
    void firstReportRecordsVisitAndUpdatesSession() {
        // given
        givenSessions(P1);

        // when
        report(P1, A);

        // then
        assertThat(count(A)).isEqualTo(1);
        assertThat(actor.snapshot().history().find(A).orElseThrow().lastVisited())
                .isEqualTo(T0);
        assertThat(leaf(P1).cwd()).contains(A);
        assertThat(events)
                .containsExactly(
                        new AppEvent.HistoryChanged(actor.snapshot().history()), new AppEvent.SessionCwdChanged(P1, A));
    }

    @Test
    @DisplayName("방문 시각은 주입한 시계에서 온다")
    void visitTimeComesFromInjectedClock() {
        // given
        givenSessions(P1);
        report(P1, A);
        clock.advance(Duration.ofMinutes(5));

        // when
        report(P1, B);
        report(P1, A);

        // then
        assertThat(actor.snapshot().history().find(A).orElseThrow().lastVisited())
                .isEqualTo(T0.plus(Duration.ofMinutes(5)));
        assertThat(actor.snapshot().history().find(A).orElseThrow().firstVisited())
                .isEqualTo(T0);
    }

    @Test
    @DisplayName("같은 창이 같은 경로를 또 알려도 횟수가 늘지 않고 이벤트도 없다")
    void repeatedReportIsIgnored() {
        // given
        givenSessions(P1);
        report(P1, A);
        events.clear();

        // when
        report(P1, A);
        report(P1, A);

        // then
        assertThat(count(A)).isEqualTo(1);
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("a 에서 b 로 갔다가 a 로 돌아오면 a 는 2회, b 는 1회다")
    void returningCountsAgain() {
        // given
        givenSessions(P1);

        // when
        report(P1, A);
        report(P1, B);
        report(P1, A);

        // then
        assertThat(count(A)).isEqualTo(2);
        assertThat(count(B)).isEqualTo(1);
    }

    @Test
    @DisplayName("두 창이 같은 경로를 알리면 창마다 따로 세어 2회다")
    void twoPanesCountSeparately() {
        // given
        givenSessions(P1, P2);

        // when
        report(P1, A);
        report(P2, A);

        // then
        assertThat(count(A)).isEqualTo(2);
    }

    @Test
    @DisplayName("모르는 창이나 끝난 창의 보고는 이력도 세션도 바꾸지 않는다")
    void reportFromUnknownOrExitedPaneIsIgnored() {
        // given
        givenSessions(P1);
        actor.submit(current -> new dev.tanager.application.state.Transition<>(
                current.withSessions(current.sessions().exited(P1, 0)), List.of(), true));
        executor.runAll();
        events.clear();

        // when
        report(P1, A);
        report(P2, A);

        // then
        assertThat(count(A)).isZero();
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("record 는 기다리지 않고 돌아오며 상태는 액터가 명령을 돌릴 때 바뀐다")
    void recordReturnsBeforeTheActorRuns() {
        // given
        givenSessions(P1);

        // when
        visits.record(P1, A);

        // then
        assertThat(executor.pending()).isEqualTo(1);
        assertThat(count(A)).isZero();
        executor.runAll();
        assertThat(count(A)).isEqualTo(1);
    }
}
