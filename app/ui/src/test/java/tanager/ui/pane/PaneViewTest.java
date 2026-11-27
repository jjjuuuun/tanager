package tanager.ui.pane;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.in.StartFailure;
import tanager.application.port.out.ScriptedTerminalStream;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.session.SessionError;

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
        assertThat(view.hint()).contains("Enter 다시 시도 · Tab 다른 셸 · Esc 닫기");
    }

    @Test
    @DisplayName("실행 중이면 머리줄은 셸 · 경로, 상태줄은 환경 · 셸 · 경로이고 덮는 글자는 없다")
    void runningHeaderAndStatus() {
        // given
        var wsl = new tanager.application.port.in.StartedSession(
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
        assertThat(windows.status()).isEqualTo("Windows · cmd · ~");
        assertThat(windows.overlay()).isEqualTo(Optional.empty());
        assertThat(ubuntu.header()).isEqualTo("bash · ~");
        assertThat(ubuntu.status()).isEqualTo("WSL · Ubuntu · bash · ~");
        assertThat(ubuntu.path()).contains("~");
        assertThat(ubuntu.warning()).isEmpty();
    }

    @Test
    @DisplayName("탭 제목은 프로파일 이름이고 아직 모르면 Tanager 다")
    void tabTitle() {
        assertThat(PaneView.tabTitle(new PaneState.Launching(FRESH))).isEqualTo("Tanager");
        assertThat(PaneView.tabTitle(new PaneState.Exited(PaneFixtures.exited(0))))
                .isEqualTo("cmd");
        assertThat(PaneView.tabTitle(new PaneState.Closed())).isEqualTo("Tanager");
    }

    private static final java.util.List<tanager.domain.session.ShellProfile> SHELLS =
            List.of(PaneFixtures.CMD, PaneFixtures.UBUNTU);

    private static PaneState.Picking picking(Optional<tanager.domain.session.ProfileId> missing, PickerExit exit) {
        return new PaneState.Picking(
                ShellPicker.atStartup(new tanager.domain.session.ProfileResolution.Choose(SHELLS, missing)), exit);
    }

    @Test
    @DisplayName("종료 띠의 안내는 Tab 다른 셸을 포함한다")
    void exitedHint() {
        assertThat(PaneView.of(new PaneState.Exited(PaneFixtures.exited(0))).hint())
                .contains("Enter 다시 시작 · Tab 다른 셸 · Esc 닫기");
    }

    @Test
    @DisplayName("다시 시도할 수 있는 실패 화면의 안내는 Tab 다른 셸을 포함하고 셸 없음 · 거부는 Esc 만이다")
    void retryAndCloseHints() {
        // then
        assertThat(PaneView.of(new PaneState.Failed(PaneFixtures.launchFailed(), FRESH))
                        .hint())
                .contains("Enter 다시 시도 · Tab 다른 셸 · Esc 닫기");
        assertThat(PaneView.of(new PaneState.Errored("x", FRESH)).hint()).contains("Enter 다시 시도 · Tab 다른 셸 · Esc 닫기");
        assertThat(PaneView.of(new PaneState.Failed(new StartFailure.NoShellAvailable(), FRESH))
                        .hint())
                .contains("Esc 닫기");
    }

    @Test
    @DisplayName("시작 때의 선택 화면은 머리줄 셸 선택 · 빈 상태줄이고 안내는 Esc 닫기로 끝난다")
    void startupPickerView() {
        // when
        PaneView view = PaneView.of(picking(Optional.empty(), new PickerExit.CloseWindow()));

        // then
        assertThat(view.header()).isEqualTo("셸 선택");
        assertThat(view.status()).isEmpty();
        assertThat(view.overlay()).isEmpty();
        assertThat(view.hint()).hasValueSatisfying(text -> assertThat(text).endsWith("Esc 닫기"));
        assertThat(PaneView.tabTitle(picking(Optional.empty(), new PickerExit.CloseWindow())))
                .isEqualTo("Tanager");
    }

    @Test
    @DisplayName("기억한 셸이 사라졌으면 안내 문장을 덮는 글자로 보인다")
    void missingShellOverlay() {
        // when
        PaneView view = PaneView.of(
                picking(Optional.of(new tanager.domain.session.ProfileId("wsl:Gone")), new PickerExit.CloseWindow()));

        // then
        assertThat(view.overlay()).contains("기억한 셸 wsl:Gone 를 찾을 수 없다 — 열 셸을 골라 주세요");
    }

    @Test
    @DisplayName("Tab 으로 연 선택 화면의 안내는 Esc 돌아가기로 끝난다")
    void returnablePickerHint() {
        // when
        PaneView view = PaneView.of(
                picking(Optional.empty(), new PickerExit.ReturnTo(new PaneState.Exited(PaneFixtures.exited(0)))));

        // then
        assertThat(view.hint()).hasValueSatisfying(text -> assertThat(text).endsWith("Esc 돌아가기"));
    }

    @Test
    @DisplayName("고른 셸을 띄우는 중이면 머리줄은 시작 중, 상태줄과 탭 제목은 그 셸이다")
    void launchingChosenShell() {
        // given
        PaneState launching = new PaneState.Launching(new Attempt.Chosen(PaneFixtures.UBUNTU, true));

        // when
        PaneView view = PaneView.of(launching);

        // then
        assertThat(view.header()).isEqualTo(PaneView.STARTING);
        assertThat(view.status()).isEqualTo("WSL · Ubuntu · bash");
        assertThat(PaneView.tabTitle(launching)).isEqualTo("Ubuntu");
    }

    @Test
    @DisplayName("셸 통합 없이 띄운 창은 머리줄 경고를 보인다(#82)")
    void integrationUnavailableShowsWarning() {
        // given
        var session = PaneFixtures.started(
                new ScriptedTerminalStream(1),
                List.of(new tanager.application.port.in.StartNotice.IntegrationUnavailable(
                        new tanager.application.port.out.Integration.Problem.NotSupported())));

        // when
        PaneView view = PaneView.of(new PaneState.Running(session));

        // then
        assertThat(view.warning()).contains(PaneView.NO_INTEGRATION);
    }

    @Test
    @DisplayName("기동 중 · 선택 화면에는 경로가 없다")
    void noPathWithoutSession() {
        // when
        PaneView launching = PaneView.of(new PaneState.Launching(new Attempt.Fresh()));

        // then
        assertThat(launching.path()).isEmpty();
        assertThat(launching.warning()).isEmpty();
    }
}
