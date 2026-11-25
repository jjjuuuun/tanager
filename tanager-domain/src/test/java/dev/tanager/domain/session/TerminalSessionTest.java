package dev.tanager.domain.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tanager.domain.history.ShellEnvironment;
import dev.tanager.domain.history.WorkspacePath;
import dev.tanager.domain.shared.Result;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TerminalSessionTest {

    private static final ShellEnvironment WSL = new ShellEnvironment.Wsl("Ubuntu-24.04");
    private static final ShellEnvironment WIN = new ShellEnvironment.Windows();

    private static WorkspacePath wslPath(String p) {
        return WorkspacePath.of(WSL, p).value().orElseThrow();
    }

    private static WorkspacePath winPath(String p) {
        return WorkspacePath.of(WIN, p).value().orElseThrow();
    }

    private static ShellProfile wslProfile() {
        return new ShellProfile(
                new ProfileId("wsl-ubuntu"),
                "Ubuntu",
                ShellKind.BASH,
                WSL,
                new LaunchTemplate("wsl.exe", List.of("-d", "Ubuntu-24.04")),
                IntegrationMode.OSC7);
    }

    private static TerminalSession starting() {
        return TerminalSession.start(wslProfile(), wslPath("/home/me")).value().orElseThrow();
    }

    private static TerminalSession running() {
        return starting().launched(42).value().orElseThrow();
    }

    private static TerminalSession exited() {
        return running().exited(1).value().orElseThrow();
    }

    private static TerminalSession failed() {
        return starting().launchFailed("not found").value().orElseThrow();
    }

    // ---- 불변식 ----

    @Test
    @DisplayName("ProfileId 는 빈 값을 거부한다")
    void profileIdRejectsBlank() {
        assertThatThrownBy(() -> new ProfileId("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProfileId(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("LaunchTemplate 은 빈 실행 파일을 거부한다")
    void launchTemplateRejectsBlankExecutable() {
        assertThatThrownBy(() -> new LaunchTemplate("", List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("LaunchTemplate 은 인자 목록을 방어 복사하고 반환 목록은 불변이다")
    void launchTemplateCopiesArgsDefensively() {
        // given
        List<String> args = new ArrayList<>(List.of("-d", "x"));
        // when
        LaunchTemplate t = new LaunchTemplate("wsl.exe", args);
        args.add("mutated");
        // then
        assertThat(t.args()).containsExactly("-d", "x");
        assertThatThrownBy(() -> t.args().add("y")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("ShellProfile 은 null 인자를 거부한다")
    void shellProfileRejectsNull() {
        LaunchTemplate l = new LaunchTemplate("cmd.exe", List.of());
        ProfileId id = new ProfileId("cmd");
        assertThatThrownBy(() -> new ShellProfile(null, "n", ShellKind.CMD, WIN, l, IntegrationMode.NONE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ShellProfile(id, null, ShellKind.CMD, WIN, l, IntegrationMode.NONE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ShellProfile(id, "n", null, WIN, l, IntegrationMode.NONE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ShellProfile(id, "n", ShellKind.CMD, null, l, IntegrationMode.NONE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ShellProfile(id, "n", ShellKind.CMD, WIN, null, IntegrationMode.NONE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ShellProfile(id, "n", ShellKind.CMD, WIN, l, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("ShellProfile 은 빈 이름을 거부한다")
    void shellProfileRejectsBlankName() {
        LaunchTemplate l = new LaunchTemplate("cmd.exe", List.of());
        assertThatThrownBy(
                        () -> new ShellProfile(new ProfileId("cmd"), " ", ShellKind.CMD, WIN, l, IntegrationMode.NONE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- 전이 ----

    @Test
    @DisplayName("start 는 Starting(dir) 세션을 만든다")
    void startCreatesStartingSession() {
        // given
        WorkspacePath dir = wslPath("/home/me");
        // when
        Result<TerminalSession, SessionError> r = TerminalSession.start(wslProfile(), dir);
        // then
        assertThat(r.value()).contains(new TerminalSession(wslProfile(), new SessionState.Starting(dir)));
    }

    @Test
    @DisplayName("start 는 프로파일과 다른 환경의 경로를 EnvironmentMismatch 로 거부한다")
    void startRejectsEnvironmentMismatch() {
        // given
        WorkspacePath dir = winPath("C:\\work");
        // when
        Result<TerminalSession, SessionError> r = TerminalSession.start(wslProfile(), dir);
        // then
        assertThat(r.error()).contains(new SessionError.EnvironmentMismatch(WSL, WIN));
    }

    @Test
    @DisplayName("Starting 에서 launched 하면 Running(pid, dir) 이 된다")
    void launchedMovesToRunning() {
        // given
        TerminalSession s = starting();
        // when
        Result<TerminalSession, SessionError> r = s.launched(42);
        // then
        assertThat(r.value().orElseThrow().state()).isEqualTo(new SessionState.Running(42, wslPath("/home/me")));
    }

    @Test
    @DisplayName("Starting 에서 launchFailed 하면 Failed(reason) 이 된다")
    void launchFailedMovesToFailed() {
        // given
        TerminalSession s = starting();
        // when
        Result<TerminalSession, SessionError> r = s.launchFailed("not found");
        // then
        assertThat(r.value().orElseThrow().state()).isEqualTo(new SessionState.Failed("not found"));
    }

    @Test
    @DisplayName("Running 에서 cwdChanged 하면 같은 pid 로 새 cwd 를 갖는다")
    void cwdChangedUpdatesCwd() {
        // given
        TerminalSession s = running();
        WorkspacePath next = wslPath("/home/me/proj");
        // when
        Result<TerminalSession, SessionError> r = s.cwdChanged(next);
        // then
        assertThat(r.value().orElseThrow().state()).isEqualTo(new SessionState.Running(42, next));
    }

    @Test
    @DisplayName("cwdChanged 는 다른 환경의 경로를 EnvironmentMismatch 로 거부한다")
    void cwdChangedRejectsOtherEnvironment() {
        // given
        TerminalSession s = running();
        // when
        Result<TerminalSession, SessionError> r = s.cwdChanged(winPath("C:\\x"));
        // then
        assertThat(r.error()).contains(new SessionError.EnvironmentMismatch(WSL, WIN));
    }

    @Test
    @DisplayName("Running 에서 exited 하면 현재 cwd 를 마지막 경로로 갖는 Exited 가 된다")
    void exitedKeepsCurrentCwd() {
        // given
        TerminalSession s = running();
        // when
        Result<TerminalSession, SessionError> r = s.exited(1);
        // then
        assertThat(r.value().orElseThrow().state()).isEqualTo(new SessionState.Exited(1, wslPath("/home/me")));
    }

    @Test
    @DisplayName("cwdChanged 이후 exited 하면 바뀐 cwd 가 마지막 경로다")
    void exitedAfterCwdChangeUsesNewCwd() {
        // given
        WorkspacePath next = wslPath("/srv/app");
        TerminalSession s = running().cwdChanged(next).value().orElseThrow();
        // when
        Result<TerminalSession, SessionError> r = s.exited(0);
        // then
        assertThat(r.value().orElseThrow().state()).isEqualTo(new SessionState.Exited(0, next));
    }

    @Test
    @DisplayName("Exited 에서 restart 하면 마지막 경로에서 Starting 이 된다")
    void restartStartsAtLastCwd() {
        // given
        WorkspacePath next = wslPath("/srv/app");
        TerminalSession s = running()
                .cwdChanged(next)
                .value()
                .orElseThrow()
                .exited(2)
                .value()
                .orElseThrow();
        // when
        Result<TerminalSession, SessionError> r = s.restart();
        // then
        assertThat(r.value().orElseThrow().state()).isEqualTo(new SessionState.Starting(next));
    }

    @Test
    @DisplayName("전이 후에도 profile 은 그대로다")
    void profileIsPreservedAcrossTransitions() {
        // given
        TerminalSession s = exited();
        // when
        TerminalSession restarted = s.restart().value().orElseThrow();
        // then
        assertThat(restarted.profile()).isEqualTo(wslProfile());
        assertThat(running().profile()).isEqualTo(wslProfile());
    }

    static Stream<Arguments> illegalTransitions() {
        WorkspacePath p = wslPath("/home/me");
        return Stream.of(
                illegal("starting", starting(), "exited", s -> s.exited(0)),
                illegal("starting", starting(), "restart", TerminalSession::restart),
                illegal("starting", starting(), "cwdChanged", s -> s.cwdChanged(p)),
                illegal("running", running(), "restart", TerminalSession::restart),
                illegal("running", running(), "launched", s -> s.launched(1)),
                illegal("running", running(), "launchFailed", s -> s.launchFailed("x")),
                illegal("exited", exited(), "launched", s -> s.launched(1)),
                illegal("exited", exited(), "launchFailed", s -> s.launchFailed("x")),
                illegal("exited", exited(), "cwdChanged", s -> s.cwdChanged(p)),
                illegal("exited", exited(), "exited", s -> s.exited(0)),
                illegal("failed", failed(), "restart", TerminalSession::restart),
                illegal("failed", failed(), "launched", s -> s.launched(1)),
                illegal("failed", failed(), "launchFailed", s -> s.launchFailed("x")),
                illegal("failed", failed(), "exited", s -> s.exited(0)),
                illegal("failed", failed(), "cwdChanged", s -> s.cwdChanged(p)));
    }

    private static Arguments illegal(
            String label,
            TerminalSession from,
            String event,
            Function<TerminalSession, Result<TerminalSession, SessionError>> op) {
        return Arguments.of(label + " --" + event + "-->", from, event, op);
    }

    @ParameterizedTest(name = "{0} 거부")
    @MethodSource("illegalTransitions")
    @DisplayName("허용되지 않은 전이는 IllegalTransition(현재 상태, 이벤트 이름) 이고 원래 세션은 바뀌지 않는다")
    void illegalTransitionIsRejected(
            String label,
            TerminalSession from,
            String event,
            Function<TerminalSession, Result<TerminalSession, SessionError>> op) {
        // given
        SessionState before = from.state();
        // when
        Result<TerminalSession, SessionError> r = op.apply(from);
        // then
        assertThat(r.error()).contains(new SessionError.IllegalTransition(before, event));
        assertThat(from.state()).isEqualTo(before);
    }
}
