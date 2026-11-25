package dev.tanager.application.port.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tanager.application.port.out.LaunchError;
import dev.tanager.application.port.out.ScriptedTerminalStream;
import dev.tanager.domain.history.ShellEnvironment;
import dev.tanager.domain.session.IntegrationMode;
import dev.tanager.domain.session.LaunchTemplate;
import dev.tanager.domain.session.ProfileId;
import dev.tanager.domain.session.SessionState;
import dev.tanager.domain.session.ShellKind;
import dev.tanager.domain.session.ShellProfile;
import dev.tanager.domain.session.TerminalSession;
import dev.tanager.domain.session.WorkingDir;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StartResultTest {
    private static final ShellProfile CMD = new ShellProfile(
            new ProfileId("cmd"),
            "cmd",
            ShellKind.CMD,
            new ShellEnvironment.Windows(),
            new LaunchTemplate("cmd.exe", List.of()),
            IntegrationMode.NONE);

    private static TerminalSession inState(SessionState state) {
        return new TerminalSession(CMD, state);
    }

    @Test
    @DisplayName("StartedSession 은 Running 세션만 받는다")
    void startedSessionRequiresRunning() {
        // given
        ScriptedTerminalStream stream = new ScriptedTerminalStream(1);
        CompletableFuture<TerminalSession> exited = new CompletableFuture<>();

        // when / then
        assertThat(new StartedSession(
                                inState(new SessionState.Running(1, new WorkingDir.Home())), stream, exited, List.of())
                        .notices())
                .isEmpty();
        assertThatThrownBy(() -> new StartedSession(
                        inState(new SessionState.Starting(new WorkingDir.Home())), stream, exited, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StartedSession(
                        inState(new SessionState.Exited(0, new WorkingDir.Home())), stream, exited, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("LaunchFailed 는 Failed 세션만 받는다")
    void launchFailedRequiresFailed() {
        // given
        LaunchError error = new LaunchError.StartFailed("x");

        // when / then
        assertThat(new StartFailure.LaunchFailed(inState(new SessionState.Failed("x")), error, List.of()).notices())
                .isEmpty();
        assertThatThrownBy(() -> new StartFailure.LaunchFailed(
                        inState(new SessionState.Running(1, new WorkingDir.Home())), error, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
