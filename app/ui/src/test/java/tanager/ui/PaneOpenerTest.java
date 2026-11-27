package tanager.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.in.OpenChoices;
import tanager.application.port.in.OpenError;
import tanager.application.port.in.OpenPaneUseCase;
import tanager.application.port.out.OpenPlacement;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.layout.DropZone;
import tanager.domain.session.IntegrationMode;
import tanager.domain.session.LaunchTemplate;
import tanager.domain.session.ProfileId;
import tanager.domain.session.ShellKind;
import tanager.domain.session.ShellProfile;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.Result;
import tanager.domain.workspace.PaneSpec;

class PaneOpenerTest {
    private static final ShellProfile CMD = new ShellProfile(
            new ProfileId("cmd"),
            "cmd",
            ShellKind.CMD,
            new ShellEnvironment.Windows(),
            new LaunchTemplate("cmd.exe", List.of()),
            IntegrationMode.NONE);

    private final FakeOpen useCase = new FakeOpen();
    private final List<String> threads = new ArrayList<>();
    private final Executor background = task -> {
        threads.add("background");
        task.run();
    };
    private final Executor ui = task -> {
        threads.add("ui");
        task.run();
    };
    private final List<List<String>> notices = new ArrayList<>();
    private final PaneOpener opener = new PaneOpener(useCase, background, ui, notices::add);

    private static WorkspacePath path(ShellEnvironment env, String raw) {
        return WorkspacePath.of(env, raw).value().orElseThrow();
    }

    @Test
    @DisplayName("지운 배포판의 폴더를 열면 백그라운드에서 부르고 UI 에서 상태줄 알림 \"배포판 Z 를 찾을 수 없다\"(#91)")
    void openErrorNotifiesOnUi() {
        // given
        ShellEnvironment.Wsl gone = new ShellEnvironment.Wsl("Z");
        useCase.next = Result.err(new OpenError.NoProfileForEnvironment(gone));

        // when
        opener.open(path(gone, "/home/me"), Optional.empty(), Optional.of(OpenPlacement.RIGHT));

        // then
        assertThat(useCase.calls).containsExactly("open /home/me RIGHT");
        assertThat(notices).containsExactly(List.of("배포판 Z 를 찾을 수 없다"));
        assertThat(threads).containsExactly("background", "ui");
    }

    @Test
    @DisplayName("열리면 알림이 없다 — 새 창은 LayoutChanged 로 온다")
    void openSuccessIsQuiet() {
        // given
        useCase.next = Result.ok(new PaneSpec(new PaneId("p2"), Optional.empty(), Optional.empty()));

        // when
        opener.open(path(new ShellEnvironment.Windows(), "C:\\a"), Optional.of(CMD.id()), Optional.empty());

        // then
        assertThat(useCase.calls).containsExactly("open C:\\a -");
        assertThat(notices).isEmpty();
    }

    @Test
    @DisplayName("환경 노드의 새 창은 설정 자리로 그 환경의 기본 셸을 연다, 실패하면 알린다")
    void openHomeUsesDefaultPlacement() {
        // given
        useCase.next = Result.err(new OpenError.NoProfileForEnvironment(new ShellEnvironment.Windows()));

        // when
        opener.openHome(new ShellEnvironment.Windows());

        // then
        assertThat(useCase.calls).containsExactly("openHome Windows -");
        assertThat(notices).containsExactly(List.of("Windows 셸을 찾을 수 없다"));
    }

    @Test
    @DisplayName("세션의 같은 경로에 새 창은 고른 자리로 그 창 기준으로 열고, 세션이 없으면 알린다(#92)")
    void openSameUsesChosenPlacement() {
        // given
        useCase.next = Result.err(new OpenError.UnknownPane(new PaneId("p9")));

        // when
        opener.openSame(new PaneId("p9"), OpenPlacement.DOWN);

        // then
        assertThat(useCase.calls).containsExactly("openSame p9 DOWN");
        assertThat(notices).containsExactly(List.of("창 p9 에 세션이 없다"));
        assertThat(threads).containsExactly("background", "ui");
    }

    @Test
    @DisplayName("유스케이스가 예외를 던지면 알리지 않고 끝난다")
    void openExceptionIsSwallowed() {
        // given
        useCase.fail = true;

        // when
        opener.open(path(new ShellEnvironment.Windows(), "C:\\a"), Optional.empty(), Optional.empty());

        // then
        assertThat(notices).isEmpty();
    }

    @Test
    @DisplayName("열기 메뉴 후보는 백그라운드에서 구해 UI 에서 넘긴다")
    void choicesDeliveredOnUi() {
        // given
        useCase.choices = new OpenChoices(Optional.of(CMD), List.of());
        List<OpenChoices> received = new ArrayList<>();

        // when
        opener.choices(path(new ShellEnvironment.Windows(), "C:\\a"), received::add);

        // then
        assertThat(received).containsExactly(useCase.choices);
        assertThat(threads).containsExactly("background", "ui");
    }

    @Test
    @DisplayName("후보를 구하다 예외가 나면 넘기지 않는다")
    void choicesExceptionNotDelivered() {
        // given
        useCase.fail = true;
        List<OpenChoices> received = new ArrayList<>();

        // when
        opener.choices(path(new ShellEnvironment.Windows(), "C:\\a"), received::add);

        // then
        assertThat(received).isEmpty();
        assertThat(threads).containsExactly("background");
    }

    private static final class FakeOpen implements OpenPaneUseCase {
        final List<String> calls = new ArrayList<>();
        Result<PaneSpec, OpenError> next =
                Result.ok(new PaneSpec(new PaneId("p1"), Optional.empty(), Optional.empty()));
        OpenChoices choices = new OpenChoices(Optional.empty(), List.of());
        boolean fail;

        @Override
        public CompletionStage<Result<PaneSpec, OpenError>> open(
                WorkspacePath path, Optional<ProfileId> profile, Optional<OpenPlacement> placement) {
            failIfAsked();
            calls.add("open " + path.path() + " " + placement.map(Enum::name).orElse("-"));
            return CompletableFuture.completedFuture(next);
        }

        @Override
        public CompletionStage<Result<PaneSpec, OpenError>> openHome(
                ShellEnvironment env, Optional<OpenPlacement> placement) {
            failIfAsked();
            calls.add("openHome " + env.getClass().getSimpleName() + " "
                    + placement.map(Enum::name).orElse("-"));
            return CompletableFuture.completedFuture(next);
        }

        @Override
        public CompletionStage<Result<PaneSpec, OpenError>> openSame(PaneId pane, Optional<OpenPlacement> placement) {
            failIfAsked();
            calls.add(
                    "openSame " + pane.value() + " " + placement.map(Enum::name).orElse("-"));
            return CompletableFuture.completedFuture(next);
        }

        @Override
        public CompletionStage<Result<PaneSpec, OpenError>> openAt(WorkspacePath path, DropZone zone) {
            failIfAsked();
            calls.add("openAt " + path.path() + " " + zone);
            return CompletableFuture.completedFuture(next);
        }

        @Override
        public OpenChoices choices(WorkspacePath path) {
            failIfAsked();
            return choices;
        }

        private void failIfAsked() {
            if (fail) {
                throw new IllegalStateException("고장");
            }
        }
    }
}
