package tanager.ui.pane;

import static org.assertj.core.api.Assertions.assertThat;
import static tanager.ui.pane.PaneFixtures.CMD;
import static tanager.ui.pane.PaneFixtures.UBUNTU;
import static tanager.ui.pane.PaneFixtures.exited;
import static tanager.ui.pane.PaneFixtures.launchFailed;
import static tanager.ui.pane.PaneFixtures.started;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tanager.application.port.in.StartFailure;
import tanager.application.port.in.StartedSession;
import tanager.application.port.out.ScriptedTerminalStream;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.session.ProfileId;
import tanager.domain.session.ProfileResolution;
import tanager.domain.session.SessionError;
import tanager.domain.session.SessionState;
import tanager.domain.session.ShellProfile;
import tanager.domain.session.TerminalSession;

/** 창 상태 기계의 전체 표: 상태 7 × 사건 10. 빈칸 없이 다음 상태와 효과를 적는다. */
class PaneStateTableTest {
    // 표의 값들(한 번 만들어 모든 칸이 같은 객체를 쓴다 — CloseStream 은 스트림 동일성으로 비교한다)
    private static final Attempt FRESH = new Attempt.Fresh();
    private static final StartedSession RUNNING_SESSION = started(new ScriptedTerminalStream(1), List.of());
    private static final StartedSession LATE = started(new ScriptedTerminalStream(2), List.of());
    private static final TerminalSession EXITED = exited(4);
    private static final StartFailure.LaunchFailed LAUNCH_FAILED = launchFailed();

    private static final PaneState LAUNCHING = new PaneState.Launching(FRESH);
    private static final PaneState RUNNING = new PaneState.Running(RUNNING_SESSION);
    private static final PaneState EXITED_STATE = new PaneState.Exited(EXITED);
    private static final PaneState FAILED = new PaneState.Failed(LAUNCH_FAILED, FRESH);
    private static final PaneState CLOSED = new PaneState.Closed();
    private static final PaneState ERRORED = new PaneState.Errored("boom", FRESH);

    private static final List<ShellProfile> SHELLS = List.of(CMD, UBUNTU);
    private static final ShellPicker STARTUP_PICKER =
            ShellPicker.atStartup(new ProfileResolution.Choose(SHELLS, Optional.empty()));
    private static final PaneState PICKING = new PaneState.Picking(STARTUP_PICKER, new PickerExit.CloseWindow());

    private static final PaneEvent STARTED = new PaneEvent.Started(LATE);
    private static final PaneEvent START_FAILED = new PaneEvent.StartFailed(LAUNCH_FAILED);
    private static final PaneEvent SHELL_EXITED = new PaneEvent.ShellExited(EXITED);
    private static final PaneEvent ENTER = new PaneEvent.EnterPressed();
    private static final PaneEvent ESC = new PaneEvent.EscPressed();
    private static final PaneEvent CLOSE = new PaneEvent.CloseRequested();
    private static final PaneEvent ERROR = new PaneEvent.StartErrored("kaboom");
    private static final PaneEvent TAB = new PaneEvent.TabPressed();
    private static final PaneEvent PICKER = new PaneEvent.PickerInput(new PickerKey.Move(1));
    private static final PaneEvent LISTED = new PaneEvent.ShellsListed(SHELLS);

    private static PaneState reopened(Optional<ProfileId> current, PaneState previous) {
        return new PaneState.Picking(ShellPicker.reopened(SHELLS, current), new PickerExit.ReturnTo(previous));
    }

    private static final PaneEffect CLOSE_LATE = new PaneEffect.CloseStream(LATE.stream());

    private static Arguments cell(PaneState from, PaneEvent event, PaneState next, PaneEffect... effects) {
        return Arguments.of(from, event, next, List.of(effects));
    }

    static Stream<Arguments> table() {
        List<Arguments> rows = new ArrayList<>();
        // Launching: 기동 결과만 받는다
        rows.add(cell(LAUNCHING, STARTED, new PaneState.Running(LATE), new PaneEffect.Connect(LATE)));
        rows.add(cell(LAUNCHING, START_FAILED, new PaneState.Failed(LAUNCH_FAILED, FRESH)));
        rows.add(cell(LAUNCHING, SHELL_EXITED, LAUNCHING));
        rows.add(cell(LAUNCHING, ENTER, LAUNCHING));
        rows.add(cell(LAUNCHING, ESC, LAUNCHING));
        rows.add(cell(LAUNCHING, CLOSE, CLOSED));
        rows.add(cell(LAUNCHING, ERROR, new PaneState.Errored("kaboom", FRESH)));
        // Running: Enter · Esc 는 셸의 것이다. 끝나거나 닫히면 스트림을 닫는다(멱등)
        rows.add(cell(RUNNING, STARTED, RUNNING, CLOSE_LATE));
        rows.add(cell(RUNNING, START_FAILED, RUNNING));
        rows.add(cell(RUNNING, SHELL_EXITED, EXITED_STATE, new PaneEffect.CloseStream(RUNNING_SESSION.stream())));
        rows.add(cell(RUNNING, ENTER, RUNNING));
        rows.add(cell(RUNNING, ESC, RUNNING));
        rows.add(cell(RUNNING, CLOSE, CLOSED, new PaneEffect.CloseStream(RUNNING_SESSION.stream())));
        rows.add(cell(RUNNING, ERROR, RUNNING));
        // Exited
        Attempt restart = new Attempt.Restart(EXITED);
        rows.add(cell(EXITED_STATE, STARTED, EXITED_STATE, CLOSE_LATE));
        rows.add(cell(EXITED_STATE, START_FAILED, EXITED_STATE));
        rows.add(cell(EXITED_STATE, SHELL_EXITED, EXITED_STATE));
        rows.add(cell(EXITED_STATE, ENTER, new PaneState.Launching(restart), new PaneEffect.Launch(restart)));
        rows.add(cell(EXITED_STATE, ESC, CLOSED, new PaneEffect.RemovePane()));
        rows.add(cell(EXITED_STATE, CLOSE, CLOSED));
        rows.add(cell(EXITED_STATE, ERROR, EXITED_STATE));
        // Failed(기동 실패 — 다시 시도할 수 있다)
        rows.add(cell(FAILED, STARTED, FAILED, CLOSE_LATE));
        rows.add(cell(FAILED, START_FAILED, FAILED));
        rows.add(cell(FAILED, SHELL_EXITED, FAILED));
        rows.add(cell(FAILED, ENTER, LAUNCHING, new PaneEffect.Launch(FRESH)));
        rows.add(cell(FAILED, ESC, CLOSED, new PaneEffect.RemovePane()));
        rows.add(cell(FAILED, CLOSE, CLOSED));
        rows.add(cell(FAILED, ERROR, FAILED));
        // Closed: 늦게 온 스트림만 닫는다
        rows.add(cell(CLOSED, STARTED, CLOSED, CLOSE_LATE));
        rows.add(cell(CLOSED, START_FAILED, CLOSED));
        rows.add(cell(CLOSED, SHELL_EXITED, CLOSED));
        rows.add(cell(CLOSED, ENTER, CLOSED));
        rows.add(cell(CLOSED, ESC, CLOSED));
        rows.add(cell(CLOSED, CLOSE, CLOSED));
        rows.add(cell(CLOSED, ERROR, CLOSED));
        // Errored(기동이 예외로 끝났다 — 다시 시도할 수 있다)
        rows.add(cell(ERRORED, STARTED, ERRORED, CLOSE_LATE));
        rows.add(cell(ERRORED, START_FAILED, ERRORED));
        rows.add(cell(ERRORED, SHELL_EXITED, ERRORED));
        rows.add(cell(ERRORED, ENTER, LAUNCHING, new PaneEffect.Launch(FRESH)));
        rows.add(cell(ERRORED, ESC, CLOSED, new PaneEffect.RemovePane()));
        rows.add(cell(ERRORED, CLOSE, CLOSED));
        rows.add(cell(ERRORED, ERROR, ERRORED));
        // 새 사건 TAB · PICKER · LISTED
        for (PaneState quiet : List.of(LAUNCHING, RUNNING, CLOSED)) {
            rows.add(cell(quiet, TAB, quiet));
            rows.add(cell(quiet, PICKER, quiet));
            rows.add(cell(quiet, LISTED, quiet));
        }
        rows.add(cell(EXITED_STATE, TAB, EXITED_STATE, new PaneEffect.ListShells()));
        rows.add(cell(EXITED_STATE, PICKER, EXITED_STATE));
        rows.add(cell(EXITED_STATE, LISTED, reopened(Optional.of(CMD.id()), EXITED_STATE)));
        rows.add(cell(FAILED, TAB, FAILED, new PaneEffect.ListShells()));
        rows.add(cell(FAILED, PICKER, FAILED));
        rows.add(cell(FAILED, LISTED, reopened(Optional.of(CMD.id()), FAILED)));
        rows.add(cell(ERRORED, TAB, ERRORED, new PaneEffect.ListShells()));
        rows.add(cell(ERRORED, PICKER, ERRORED));
        rows.add(cell(ERRORED, LISTED, reopened(Optional.empty(), ERRORED)));
        // Picking(셸 선택 화면, 시작 때 연 것)
        Attempt chosen = new Attempt.Chosen(CMD, true);
        PaneState moved = new PaneState.Picking(
                ((PickerStep.Stay) STARTUP_PICKER.on(new PickerKey.Move(1))).picker(), new PickerExit.CloseWindow());
        rows.add(cell(PICKING, STARTED, PICKING, CLOSE_LATE));
        rows.add(cell(PICKING, START_FAILED, PICKING));
        rows.add(cell(PICKING, SHELL_EXITED, PICKING));
        rows.add(cell(PICKING, ENTER, new PaneState.Launching(chosen), new PaneEffect.Launch(chosen)));
        rows.add(cell(PICKING, ESC, CLOSED, new PaneEffect.RemovePane()));
        rows.add(cell(PICKING, CLOSE, CLOSED));
        rows.add(cell(PICKING, ERROR, PICKING));
        rows.add(cell(PICKING, TAB, PICKING));
        rows.add(cell(PICKING, PICKER, moved));
        rows.add(cell(PICKING, LISTED, PICKING));
        return rows.stream();
    }

    @ParameterizedTest(name = "[{index}] {0} + {1}")
    @MethodSource("table")
    @DisplayName("상태 × 사건 표의 모든 칸이 정해진 다음 상태와 효과를 낸다")
    void everyCell(PaneState from, PaneEvent event, PaneState next, List<PaneEffect> effects) {
        // when
        Transition t = from.on(event);

        // then
        assertThat(t.next()).isEqualTo(next);
        assertThat(t.effects()).containsExactlyElementsOf(effects);
    }

    @Test
    @DisplayName("표는 상태 7 × 사건 10 = 70칸을 빠짐없이 덮는다")
    void tableIsComplete() {
        assertThat(table().count()).isEqualTo(70);
    }

    @Test
    @DisplayName("요청이 거부돼 실패했으면 Enter 로 다시 시도하지 않는다")
    void rejectedIsNotRetryable() {
        // given
        PaneState rejected = new PaneState.Failed(
                new StartFailure.Rejected(new SessionError.EnvironmentMismatch(
                        new ShellEnvironment.Windows(), new ShellEnvironment.Wsl("Ubuntu"))),
                FRESH);

        // when
        Transition t = rejected.on(ENTER);

        // then
        assertThat(t.next()).isEqualTo(rejected);
        assertThat(t.effects()).isEmpty();
    }

    @Test
    @DisplayName("기동 알림이 있으면 연결 뒤에 알림 효과가, 기동 실패의 알림도 알림 효과가 된다")
    void noticesFollowConnectAndFailure() {
        // given
        var notice = new tanager.application.port.in.StartNotice.StartDirInvalid("zzz");
        TerminalSession failed = launchFailed().failed();
        StartFailure.LaunchFailed withNotice = new StartFailure.LaunchFailed(
                failed, new tanager.application.port.out.LaunchError.StartFailed("x"), List.of(notice));

        // when
        Transition t = LAUNCHING.on(new PaneEvent.StartFailed(withNotice));

        // then
        assertThat(t.effects()).containsExactly(new PaneEffect.ShowNotices(List.of(notice)));
        assertThat(failed.state()).isInstanceOf(SessionState.Failed.class);
    }
}
