package tanager.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.in.AppEvent;
import tanager.application.state.AppState;
import tanager.application.state.FakeClock;
import tanager.application.state.ManualExecutor;
import tanager.application.state.StateActor;
import tanager.domain.history.HistoryPolicy;
import tanager.domain.history.PathPattern;
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

/** 방문 기록과 이력 규칙 · 사라진 경로 표시(#186). */
class RecordVisitRulesTest {
    private static final ShellEnvironment UBUNTU = new ShellEnvironment.Wsl("Ubuntu");
    private static final ShellProfile BASH = new ShellProfile(
            new ProfileId("wsl:Ubuntu"),
            "Ubuntu",
            ShellKind.BASH,
            UBUNTU,
            new LaunchTemplate("wsl.exe", List.of()),
            IntegrationMode.OSC7);
    private static final PaneId P1 = new PaneId("p1");
    private static final Instant T0 = Instant.parse("2026-11-28T00:00:00Z");

    private final ManualExecutor executor = new ManualExecutor();
    private final List<AppEvent> events = new ArrayList<>();
    private StateActor actor;
    private VisitRecorder visits;

    private static WorkspacePath wsl(String raw) {
        return WorkspacePath.of(UBUNTU, raw).value().orElseThrow();
    }

    private void given(AppState state) {
        TerminalSession running = TerminalSession.start(BASH, new WorkingDir.Home())
                .value()
                .orElseThrow()
                .launched(10)
                .value()
                .orElseThrow();
        actor = new StateActor(state.withSessions(LiveSessions.empty().launched(P1, running)), executor);
        actor.subscribe(events::add);
        visits = new VisitRecorder(actor, new FakeClock(T0));
    }

    @Test
    @DisplayName("제외 규칙에 맞는 경로로 옮기면 기록하지 않고 HistoryChanged 도 없다 — 세션 경로는 갱신한다")
    void excludedNotRecorded() {
        // given
        PathPattern tmp = PathPattern.of("/tmp/**").value().orElseThrow();
        given(AppState.initial(WorkHistory.empty(new HistoryPolicy(2000, List.of(tmp)))));
        // when
        visits.record(P1, wsl("/tmp/x"));
        executor.runAll();
        // then
        assertThat(actor.snapshot().history().entries()).isEmpty();
        assertThat(events).containsExactly(new AppEvent.SessionCwdChanged(P1, wsl("/tmp/x")));
    }

    @Test
    @DisplayName("사라졌다고 표시한 경로로 셸이 옮기면 그 경로는 있으므로 표시를 푼다")
    void visitClearsMissing() {
        // given
        WorkspacePath gone = wsl("/home/me/old");
        WorkHistory h = WorkHistory.empty(HistoryPolicy.DEFAULT).recordVisit(gone, T0);
        given(AppState.initial(h).withMissing(Set.of(gone)));
        // when
        visits.record(P1, gone);
        executor.runAll();
        // then
        assertThat(actor.snapshot().missing()).isEmpty();
        assertThat(events).contains(new AppEvent.MissingPathsChanged(Set.of()));
    }
}
