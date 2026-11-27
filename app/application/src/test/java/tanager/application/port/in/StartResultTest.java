package tanager.application.port.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.out.ConfigError;
import tanager.application.port.out.LaunchError;
import tanager.application.port.out.ScriptedTerminalStream;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.session.IntegrationMode;
import tanager.domain.session.LaunchTemplate;
import tanager.domain.session.ProfileId;
import tanager.domain.session.ProfileResolution;
import tanager.domain.session.SessionState;
import tanager.domain.session.ShellKind;
import tanager.domain.session.ShellProfile;
import tanager.domain.session.TerminalSession;
import tanager.domain.session.WorkingDir;
import tanager.domain.shared.PaneId;

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

    private static final tanager.application.port.out.TerminalSize SIZE =
            new tanager.application.port.out.TerminalSize(100, 30);

    @Test
    @DisplayName("기억을 켠 요청은 프로파일이 있어야 한다")
    void rememberRequiresProfile() {
        assertThatThrownBy(() -> new StartRequest(new PaneId("p1"), Optional.empty(), true, Optional.empty(), SIZE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("remembered 요청은 프로파일 · 경로 없이 기억도 켜지 않는다")
    void rememberedRequestIsEmpty() {
        // when
        StartRequest request = StartRequest.remembered(new PaneId("p1"), SIZE);
        // then
        assertThat(request.pane()).isEqualTo(new PaneId("p1"));
        assertThat(request.profile()).isEmpty();
        assertThat(request.rememberAsDefault()).isFalse();
        assertThat(request.dir()).isEmpty();
        assertThat(request.size()).isEqualTo(SIZE);
    }

    @Test
    @DisplayName("chosen 요청은 고른 프로파일과 기억 여부를 담고 경로는 비운다")
    void chosenRequestCarriesProfileAndRemember() {
        // when
        StartRequest request = StartRequest.chosen(new PaneId("p1"), new ProfileId("cmd"), true, SIZE);
        // then
        assertThat(request.profile()).contains(new ProfileId("cmd"));
        assertThat(request.rememberAsDefault()).isTrue();
        assertThat(request.dir()).isEmpty();
    }

    @Test
    @DisplayName("ChoiceRequired 는 알림 목록을 방어 복사한다")
    void choiceRequiredCopiesNotices() {
        // given
        List<StartNotice> mutable = new ArrayList<>(List.of(new StartNotice.StartDirInvalid("foo")));
        StartFailure.ChoiceRequired failure =
                new StartFailure.ChoiceRequired(new ProfileResolution.Choose(List.of(CMD), Optional.empty()), mutable);
        // when
        mutable.clear();
        // then
        assertThat(failure.notices()).hasSize(1);
    }

    @Test
    @DisplayName("DefaultNotSaved 는 셸 id 와 저장 실패 이유를 문장으로 설명한다")
    void defaultNotSavedDescribes() {
        // when
        String text = new StartNotice.DefaultNotSaved(new ProfileId("cmd"), new ConfigError.WouldOverwrite("손상된 파일이다"))
                .describe();
        // then
        assertThat(text).isEqualTo("기본 셸 cmd 을 저장하지 못했다 — 설정 파일을 덮어쓰지 않았다: 손상된 파일이다");
    }
}
