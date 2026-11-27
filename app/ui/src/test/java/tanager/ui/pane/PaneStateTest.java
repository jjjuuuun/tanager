package tanager.ui.pane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.in.StartFailure;
import tanager.application.port.in.StartNotice;
import tanager.application.port.in.StartedSession;
import tanager.application.port.out.LaunchError;
import tanager.application.port.out.ScriptedTerminalStream;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.session.IntegrationMode;
import tanager.domain.session.LaunchTemplate;
import tanager.domain.session.ProfileId;
import tanager.domain.session.ProfileResolution;
import tanager.domain.session.SessionError;
import tanager.domain.session.SessionState;
import tanager.domain.session.ShellKind;
import tanager.domain.session.ShellProfile;
import tanager.domain.session.TerminalSession;
import tanager.domain.session.WorkingDir;

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

    private static StartedSession ubuntuSession() {
        return new StartedSession(
                PaneFixtures.running(PaneFixtures.UBUNTU),
                new ScriptedTerminalStream(1),
                new CompletableFuture<>(),
                List.of());
    }

    private static WorkspacePath wsl(String raw) {
        return WorkspacePath.of(new ShellEnvironment.Wsl("Ubuntu"), raw).value().orElseThrow();
    }

    @Test
    @DisplayName("실행 중에 셸이 경로를 알리면 머리줄이 그 경로를 보인다(#82)")
    void runningFollowsReportedCwd() {
        // given
        PaneState running = new PaneState.Running(ubuntuSession());

        // when
        PaneState next = running.on(new PaneEvent.CwdReported(wsl("/srv/app"))).next();

        // then
        assertThat(PaneView.of(next).header()).isEqualTo("bash · /srv/app");
        assertThat(PaneView.of(next).status()).isEqualTo("WSL · Ubuntu · bash · /srv/app");
    }

    @Test
    @DisplayName("셸이 끝나면 종료 화면도 마지막으로 알린 경로를 보인다")
    void exitedKeepsLastReportedCwd() {
        // given: 종료 사건의 세션은 처음 Running 에서 만든 것이라 홈을 가리킨다
        StartedSession started = ubuntuSession();
        PaneState moved = new PaneState.Running(started)
                .on(new PaneEvent.CwdReported(wsl("/srv/app")))
                .next();
        TerminalSession stale = started.session().exited(0).value().orElseThrow();

        // when
        PaneState exited = moved.on(new PaneEvent.ShellExited(stale)).next();

        // then
        assertThat(PaneView.of(exited).header()).isEqualTo("bash · /srv/app");
        assertThat(((PaneState.Exited) exited).session().state())
                .isEqualTo(new SessionState.Exited(0, new WorkingDir.At(wsl("/srv/app"))));
    }

    @Test
    @DisplayName("다른 환경의 경로 보고는 무시한다")
    void ignoresCwdFromOtherEnvironment() {
        // given
        PaneState running = new PaneState.Running(ubuntuSession());
        WorkspacePath windows = WorkspacePath.of(new ShellEnvironment.Windows(), "C:\\x")
                .value()
                .orElseThrow();

        // when
        Transition t = running.on(new PaneEvent.CwdReported(windows));

        // then
        assertThat(t.next()).isEqualTo(running);
        assertThat(t.effects()).isEmpty();
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
        assertThat(view.hint()).contains("Enter 다시 시작 · Tab 다른 셸 · Esc 닫기");
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

    // ---- 셸 선택 화면(#70) ----

    private static final ShellProfile UBUNTU = PaneFixtures.UBUNTU;
    private static final List<ShellProfile> SHELLS = List.of(PROFILE, UBUNTU);

    private static StartFailure.ChoiceRequired choiceRequired(Optional<ProfileId> missing, List<StartNotice> notices) {
        return new StartFailure.ChoiceRequired(new ProfileResolution.Choose(SHELLS, missing), notices);
    }

    private static PaneState startupPicking() {
        return new PaneState.Picking(
                ShellPicker.atStartup(new ProfileResolution.Choose(SHELLS, Optional.empty())),
                new PickerExit.CloseWindow());
    }

    @Test
    @DisplayName("선택이 필요하다는 결과를 받으면 선택 화면이 되고 알림만 효과로 나온다")
    void choiceRequiredOpensPicker() {
        // given
        StartNotice notice = new StartNotice.StartDirInvalid("zzz");

        // when
        Transition t = new PaneState.Launching(new Attempt.Fresh())
                .on(new PaneEvent.StartFailed(choiceRequired(Optional.empty(), List.of(notice))));
        Transition quiet = new PaneState.Launching(new Attempt.Fresh())
                .on(new PaneEvent.StartFailed(choiceRequired(Optional.empty(), List.of())));

        // then
        assertThat(t.next()).isEqualTo(startupPicking());
        assertThat(t.effects()).containsExactly(new PaneEffect.ShowNotices(List.of(notice)));
        assertThat(quiet.effects()).isEmpty();
    }

    @Test
    @DisplayName("기억한 셸이 사라졌다는 정보가 선택 화면에 담긴다")
    void choiceRequiredKeepsMissingId() {
        // when
        Transition t = new PaneState.Launching(new Attempt.Fresh())
                .on(new PaneEvent.StartFailed(choiceRequired(Optional.of(new ProfileId("wsl:Gone")), List.of())));

        // then
        assertThat(t.next())
                .isInstanceOfSatisfying(
                        PaneState.Picking.class,
                        picking -> assertThat(picking.picker().missing()).contains(new ProfileId("wsl:Gone")));
    }

    @Test
    @DisplayName("선택 화면에서 숫자를 누르면 그 셸을 기억 켜짐으로 띄운다")
    void digitLaunchesChosenShell() {
        // when
        Transition t = startupPicking().on(new PaneEvent.PickerInput(new PickerKey.Digit(2)));

        // then
        Attempt chosen = new Attempt.Chosen(UBUNTU, true);
        assertThat(t.next()).isEqualTo(new PaneState.Launching(chosen));
        assertThat(t.effects()).containsExactly(new PaneEffect.Launch(chosen));
    }

    @Test
    @DisplayName("선택 화면에서 행을 클릭하면 그 셸을 띄운다")
    void clickLaunchesChosenShell() {
        // when
        Transition t = startupPicking().on(new PaneEvent.PickerInput(new PickerKey.Click(0)));

        // then
        assertThat(t.next()).isEqualTo(new PaneState.Launching(new Attempt.Chosen(PROFILE, true)));
    }

    @Test
    @DisplayName("기억을 끄고 Enter 를 누르면 기억 없이 띄운다")
    void toggleRememberThenEnter() {
        // when
        Transition toggled = startupPicking().on(new PaneEvent.PickerInput(new PickerKey.ToggleRemember()));
        Transition entered = toggled.next().on(new PaneEvent.EnterPressed());

        // then
        Attempt chosen = new Attempt.Chosen(PROFILE, false);
        assertThat(entered.next()).isEqualTo(new PaneState.Launching(chosen));
        assertThat(entered.effects()).containsExactly(new PaneEffect.Launch(chosen));
    }

    @Test
    @DisplayName("종료 띠에서 Tab → 목록 도착 → Esc 하면 선택 화면을 거쳐 같은 종료 상태로 돌아온다")
    void tabOpensPickerAndEscReturns() {
        // given
        PaneState exited = new PaneState.Exited(exited(0));

        // when
        Transition tab = exited.on(new PaneEvent.TabPressed());
        Transition listed = tab.next().on(new PaneEvent.ShellsListed(SHELLS));
        Transition esc = listed.next().on(new PaneEvent.EscPressed());

        // then
        assertThat(tab.next()).isEqualTo(exited);
        assertThat(tab.effects()).containsExactly(new PaneEffect.ListShells());
        assertThat(listed.next())
                .isEqualTo(new PaneState.Picking(
                        ShellPicker.reopened(SHELLS, Optional.of(PROFILE.id())), new PickerExit.ReturnTo(exited)));
        assertThat(listed.next()).isInstanceOfSatisfying(PaneState.Picking.class, p -> {
            assertThat(p.picker().remember()).isFalse();
            assertThat(p.picker().selected()).isZero();
        });
        assertThat(esc.next()).isSameAs(exited);
        assertThat(esc.effects()).isEmpty();
    }

    @Test
    @DisplayName("시작 때 연 선택 화면에서 Esc 는 창을 닫는다")
    void escInStartupPickerClosesWindow() {
        // when
        Transition t = startupPicking().on(new PaneEvent.EscPressed());

        // then
        assertThat(t.next()).isEqualTo(new PaneState.Closed());
        assertThat(t.effects()).containsExactly(new PaneEffect.RemovePane());
    }

    @Test
    @DisplayName("셸이 없음 · 요청 거부 화면에서 Tab 은 아무 일도 하지 않는다")
    void tabIgnoredWhenNotRetryable() {
        // given
        PaneState noShell = new PaneState.Failed(new StartFailure.NoShellAvailable(), new Attempt.Fresh());
        PaneState rejected = new PaneState.Failed(
                new StartFailure.Rejected(new SessionError.EnvironmentMismatch(
                        new ShellEnvironment.Windows(), new ShellEnvironment.Wsl("U"))),
                new Attempt.Fresh());

        // then
        for (PaneState state : List.of(noShell, rejected)) {
            Transition t = state.on(new PaneEvent.TabPressed());
            assertThat(t.next()).isEqualTo(state);
            assertThat(t.effects()).isEmpty();
        }
    }

    @Test
    @DisplayName("빈 셸 목록이 도착하면 선택 화면을 열지 않는다")
    void emptyListIsIgnored() {
        // given
        PaneState exited = new PaneState.Exited(exited(0));

        // when
        Transition t = exited.on(new PaneEvent.ShellsListed(List.of()));

        // then
        assertThat(t.next()).isEqualTo(exited);
        assertThat(t.effects()).isEmpty();
    }

    @Test
    @DisplayName("고른 셸의 기동이 실패하면 같은 선택으로 다시 시도할 수 있다")
    void chosenAttemptIsRetried() {
        // given
        Attempt chosen = new Attempt.Chosen(PROFILE, true);
        StartFailure.LaunchFailed failure = launchFailed();

        // when
        Transition failed = new PaneState.Launching(chosen).on(new PaneEvent.StartFailed(failure));
        Transition retry = failed.next().on(new PaneEvent.EnterPressed());

        // then
        assertThat(failed.next()).isEqualTo(new PaneState.Failed(failure, chosen));
        assertThat(retry.next()).isEqualTo(new PaneState.Launching(chosen));
        assertThat(retry.effects()).containsExactly(new PaneEffect.Launch(chosen));
    }

    @Test
    @DisplayName("고른 셸을 띄우다 기억한 셸이 사라졌다는 결과를 받아도 선택 화면이 된다")
    void choiceRequiredWhileLaunchingChosen() {
        // when
        Transition t = new PaneState.Launching(new Attempt.Chosen(UBUNTU, false))
                .on(new PaneEvent.StartFailed(choiceRequired(Optional.of(new ProfileId("wsl:Gone")), List.of())));

        // then
        assertThat(t.next()).isInstanceOf(PaneState.Picking.class);
    }

    @Test
    @DisplayName("선택이 필요하다는 실패는 Failed 상태에 담을 수 없다")
    void failedRejectsChoiceRequired() {
        assertThatThrownBy(() -> new PaneState.Failed(choiceRequired(Optional.empty(), List.of()), new Attempt.Fresh()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ReturnTo 는 종료 · 실패 · 오류 상태만 받는다")
    void returnToAcceptsOnlyTerminalStates() {
        // given
        List<PaneState> invalid = List.of(
                new PaneState.Running(started(new ScriptedTerminalStream(1), List.of())),
                new PaneState.Launching(new Attempt.Fresh()),
                startupPicking(),
                new PaneState.Closed());

        // then
        for (PaneState state : invalid) {
            assertThatThrownBy(() -> new PickerExit.ReturnTo(state)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("Tab 으로 연 선택 화면도 닫기 요청을 받으면 닫힌다")
    void closeRequestedWhileReturnablePicker() {
        // given
        PaneState exited = new PaneState.Exited(exited(0));
        PaneState picking =
                new PaneState.Picking(ShellPicker.reopened(SHELLS, Optional.empty()), new PickerExit.ReturnTo(exited));

        // when
        Transition t = picking.on(new PaneEvent.CloseRequested());

        // then
        assertThat(t.next()).isEqualTo(new PaneState.Closed());
        assertThat(t.effects()).isEmpty();
    }

    // ---- 복원한 창의 프로파일이 사라짐(#179) ----

    private static PaneState restoredPicking(WorkspacePath saved) {
        return new PaneState.Launching(new Attempt.Fresh(Optional.of(new ProfileId("wsl:Old")), Optional.of(saved)))
                .on(new PaneEvent.StartFailed(choiceRequired(Optional.of(new ProfileId("wsl:Old")), List.of())))
                .next();
    }

    @Test
    @DisplayName("복원한 창의 프로파일이 없어 연 선택 화면에서 같은 환경의 셸을 고르면 저장된 경로에서 연다")
    void restoredPaneChoosingSameEnvironmentKeepsSavedPath() {
        // given
        WorkspacePath saved = wsl("/home/me/project");

        // when Ubuntu(2번)를 고른다
        Transition t = restoredPicking(saved).on(new PaneEvent.PickerInput(new PickerKey.Digit(2)));

        // then
        Attempt chosen = new Attempt.Chosen(UBUNTU, true, Optional.of(saved));
        assertThat(t.next()).isEqualTo(new PaneState.Launching(chosen));
        assertThat(t.effects()).containsExactly(new PaneEffect.Launch(chosen));
    }

    @Test
    @DisplayName("복원한 창의 선택 화면에서 다른 환경의 셸을 고르면 그 셸의 시작 경로에서 연다")
    void restoredPaneChoosingOtherEnvironmentDropsSavedPath() {
        // given
        WorkspacePath saved = wsl("/home/me/project");

        // when cmd(1번)를 Enter 로 고른다
        Transition t = restoredPicking(saved).on(new PaneEvent.EnterPressed());

        // then
        assertThat(t.next()).isEqualTo(new PaneState.Launching(new Attempt.Chosen(PROFILE, true)));
    }
}
