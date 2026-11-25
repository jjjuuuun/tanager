package dev.tanager.ui.pane;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tanager.application.port.in.StartFailure;
import dev.tanager.application.port.out.ScriptedTerminalStream;
import dev.tanager.domain.history.ShellEnvironment;
import dev.tanager.domain.session.SessionError;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaneViewTest {
    private static final Attempt FRESH = new Attempt.Fresh();

    @Test
    @DisplayName("셸이 하나도 없으면 다시 시작하라는 안내와 Esc 만 보인다")
    void noShellAvailable() {
        // when
        PaneView view = PaneView.of(new PaneState.Failed(new StartFailure.NoShellAvailable(), FRESH));

        // then
        assertThat(view.overlay()).hasValueSatisfying(text -> assertThat(text).contains("앱을 다시 시작해 주세요"));
        assertThat(view.hint()).contains("Esc 닫기");
    }

    @Test
    @DisplayName("요청이 거부되면 이유와 Esc 만 보인다")
    void rejected() {
        // given
        StartFailure rejected = new StartFailure.Rejected(
                new SessionError.EnvironmentMismatch(new ShellEnvironment.Windows(), new ShellEnvironment.Wsl("U")));

        // when
        PaneView view = PaneView.of(new PaneState.Failed(rejected, FRESH));

        // then
        assertThat(view.overlay()).hasValueSatisfying(text -> assertThat(text).contains("환경"));
        assertThat(view.hint()).contains("Esc 닫기");
    }

    @Test
    @DisplayName("기동이 예외로 끝나면 그 내용과 다시 시도 안내가 보인다")
    void errored() {
        // when
        PaneView view = PaneView.of(new PaneState.Errored("터졌다", FRESH));

        // then
        assertThat(view.overlay()).hasValueSatisfying(text -> assertThat(text).contains("터졌다"));
        assertThat(view.hint()).contains("Enter 다시 시도 · Esc 닫기");
    }

    @Test
    @DisplayName("실행 중이면 머리줄은 셸 · 경로, 상태줄은 환경 · 셸이고 덮는 글자는 없다")
    void runningHeaderAndStatus() {
        // given
        var wsl = new dev.tanager.application.port.in.StartedSession(
                PaneFixtures.running(PaneFixtures.UBUNTU),
                new ScriptedTerminalStream(1),
                new CompletableFuture<>(),
                List.of());

        // when
        PaneView windows =
                PaneView.of(new PaneState.Running(PaneFixtures.started(new ScriptedTerminalStream(2), List.of())));
        PaneView ubuntu = PaneView.of(new PaneState.Running(wsl));

        // then
        assertThat(windows.header()).isEqualTo("cmd · ~");
        assertThat(windows.status()).isEqualTo("Windows · cmd");
        assertThat(windows.overlay()).isEqualTo(Optional.empty());
        assertThat(ubuntu.header()).isEqualTo("bash · ~");
        assertThat(ubuntu.status()).isEqualTo("WSL · Ubuntu · bash");
    }

    @Test
    @DisplayName("탭 제목은 프로파일 이름이고 아직 모르면 Tanager 다")
    void tabTitle() {
        assertThat(PaneView.tabTitle(new PaneState.Launching(FRESH))).isEqualTo("Tanager");
        assertThat(PaneView.tabTitle(new PaneState.Exited(PaneFixtures.exited(0))))
                .isEqualTo("cmd");
        assertThat(PaneView.tabTitle(new PaneState.Closed())).isEqualTo("Tanager");
    }
}
