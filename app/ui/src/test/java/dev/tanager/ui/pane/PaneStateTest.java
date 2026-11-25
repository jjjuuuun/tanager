package dev.tanager.ui.pane;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tanager.application.port.in.StartFailure;
import dev.tanager.application.port.in.StartNotice;
import dev.tanager.application.port.in.StartedSession;
import dev.tanager.application.port.out.LaunchError;
import dev.tanager.application.port.out.ScriptedTerminalStream;
import dev.tanager.domain.history.ShellEnvironment;
import dev.tanager.domain.session.IntegrationMode;
import dev.tanager.domain.session.LaunchTemplate;
import dev.tanager.domain.session.ProfileId;
import dev.tanager.domain.session.ShellKind;
import dev.tanager.domain.session.ShellProfile;
import dev.tanager.domain.session.TerminalSession;
import dev.tanager.domain.session.WorkingDir;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaneStateTest {
    private static final ShellProfile PROFILE = new ShellProfile(
            new ProfileId("cmd"),
            "cmd",
            ShellKind.CMD,
            new ShellEnvironment.Windows(),
            new LaunchTemplate("cmd.exe", List.of()),
            IntegrationMode.NONE);

    private static TerminalSession running() {
        return TerminalSession.start(PROFILE, new WorkingDir.Home())
                .value()
                .orElseThrow()
                .launched(10)
                .value()
                .orElseThrow();
    }

    private static TerminalSession exited(int code) {
        return running().exited(code).value().orElseThrow();
    }

    private static StartedSession started(ScriptedTerminalStream stream, List<StartNotice> notices) {
        return new StartedSession(running(), stream, new CompletableFuture<>(), notices);
    }

    private static StartFailure.LaunchFailed launchFailed() {
        TerminalSession failed = TerminalSession.start(PROFILE, new WorkingDir.Home())
                .value()
                .orElseThrow()
                .launchFailed("boom")
                .value()
                .orElseThrow();
        return new StartFailure.LaunchFailed(failed, new LaunchError.StartFailed("boom"), List.of());
    }

    @Test
    @DisplayName("Launching 에서 Started 를 받으면 Running 이 되고 Connect 와 ShowNotices 효과가 나온다")
    void startedConnectsAndShowsNotices() {
        // given
        StartNotice notice = new StartNotice.StartDirInvalid("zzz");
        StartedSession session = started(new ScriptedTerminalStream(1), List.of(notice));

        // when
        Transition t = new PaneState.Launching(new Attempt.Fresh()).on(new PaneEvent.Started(session));

        // then
        assertThat(t.next()).isEqualTo(new PaneState.Running(session));
        assertThat(t.effects())
                .containsExactly(new PaneEffect.Connect(session), new PaneEffect.ShowNotices(List.of(notice)));
    }

    @Test
    @DisplayName("Running 에서 셸이 끝나면 Exited 가 되고 화면에 종료 코드와 안내가 보인다")
    void shellExitShowsOverlay() {
        // given
        TerminalSession exited = exited(2);
        PaneState running = new PaneState.Running(started(new ScriptedTerminalStream(1), List.of()));

        // when
        Transition t = running.on(new PaneEvent.ShellExited(exited));
        PaneView view = PaneView.of(t.next());

        // then
        assertThat(t.next()).isEqualTo(new PaneState.Exited(exited));
        assertThat(view.overlay()).contains("종료 코드 2");
        assertThat(view.hint()).contains("Enter 다시 시작 · Esc 닫기");
    }

    @Test
    @DisplayName("Exited 에서 Enter 는 다시 시작 한 번만 일으키고 Launching 에서 Enter 는 무시한다")
    void enterRestartsOnce() {
        // given
        TerminalSession exited = exited(0);
        PaneState state = new PaneState.Exited(exited);

        // when
        Transition first = state.on(new PaneEvent.EnterPressed());
        Transition second = first.next().on(new PaneEvent.EnterPressed());

        // then
        Attempt restart = new Attempt.Restart(exited);
        assertThat(first.next()).isEqualTo(new PaneState.Launching(restart));
        assertThat(first.effects()).containsExactly(new PaneEffect.Launch(restart));
        assertThat(second.next()).isEqualTo(first.next());
        assertThat(second.effects()).isEmpty();
    }

    @Test
    @DisplayName("Exited 에서 Esc 는 창을 닫고 Launching 에서 Esc 는 무시한다")
    void escClosesExitedPane() {
        // given
        PaneState exited = new PaneState.Exited(exited(0));

        // when
        Transition closed = exited.on(new PaneEvent.EscPressed());
        Transition ignored = new PaneState.Launching(new Attempt.Fresh()).on(new PaneEvent.EscPressed());

        // then
        assertThat(closed.next()).isEqualTo(new PaneState.Closed());
        assertThat(closed.effects()).containsExactly(new PaneEffect.RemovePane());
        assertThat(ignored.effects()).isEmpty();
    }

    @Test
    @DisplayName("닫힌 창에 늦게 도착한 Started 는 스트림을 닫는 효과만 낸다")
    void lateStartedAfterCloseClosesStream() {
        // given
        ScriptedTerminalStream stream = new ScriptedTerminalStream(5);
        StartedSession late = started(stream, List.of());

        // when
        Transition t = new PaneState.Closed().on(new PaneEvent.Started(late));

        // then
        assertThat(t.next()).isEqualTo(new PaneState.Closed());
        assertThat(t.effects()).containsExactly(new PaneEffect.CloseStream(stream));
    }

    @Test
    @DisplayName("Launching 에서 CloseRequested 가 오고 뒤늦게 Started 가 와도 스트림은 닫힌다")
    void closeWhileLaunchingThenStartedClosesStream() {
        // given
        ScriptedTerminalStream stream = new ScriptedTerminalStream(6);
        Transition closing = new PaneState.Launching(new Attempt.Fresh()).on(new PaneEvent.CloseRequested());

        // when
        Transition late = closing.next().on(new PaneEvent.Started(started(stream, List.of())));

        // then
        assertThat(closing.next()).isEqualTo(new PaneState.Closed());
        assertThat(late.effects()).containsExactly(new PaneEffect.CloseStream(stream));
    }

    @Test
    @DisplayName("기동 실패는 Failed 가 되어 오류 설명을 보이고 Enter 는 같은 시도로 다시 띄운다")
    void launchFailureShowsDescriptionAndRetries() {
        // given
        StartFailure.LaunchFailed failure = launchFailed();
        Attempt attempt = new Attempt.Fresh();

        // when
        Transition failed = new PaneState.Launching(attempt).on(new PaneEvent.StartFailed(failure));
        Transition retry = failed.next().on(new PaneEvent.EnterPressed());

        // then
        assertThat(failed.next()).isEqualTo(new PaneState.Failed(failure, attempt));
        assertThat(PaneView.of(failed.next()).overlay())
                .hasValueSatisfying(
                        text -> assertThat(text).contains(failure.error().describe()));
        assertThat(retry.next()).isEqualTo(new PaneState.Launching(attempt));
        assertThat(retry.effects()).containsExactly(new PaneEffect.Launch(attempt));
    }

    @Test
    @DisplayName("셸이 하나도 없어 실패하면 Enter 는 아무 일도 하지 않는다")
    void noShellIgnoresEnter() {
        // given
        PaneState failed = new PaneState.Failed(new StartFailure.NoShellAvailable(), new Attempt.Fresh());

        // when
        Transition t = failed.on(new PaneEvent.EnterPressed());

        // then
        assertThat(t.next()).isEqualTo(failed);
        assertThat(t.effects()).isEmpty();
    }
}
