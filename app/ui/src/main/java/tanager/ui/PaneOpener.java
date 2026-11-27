package tanager.ui;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;
import tanager.application.port.in.OpenChoices;
import tanager.application.port.in.OpenError;
import tanager.application.port.in.OpenPaneUseCase;
import tanager.application.port.out.OpenPlacement;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.session.ProfileId;
import tanager.domain.shared.Result;
import tanager.domain.workspace.PaneSpec;
import tanager.ui.i18n.NoticeText;
import tanager.ui.sidebar.SidebarOpener;

/**
 * 사이드바의 열기를 {@link OpenPaneUseCase} 로 잇는다(#91). 유스케이스는 셸 탐지 · 설정 읽기로 블로킹될 수 있어 {@code background} 에서
 * 부르고, 열지 못한 이유는 {@code ui} 에서 상태줄 알림으로 보인다. 새 창 자체는 {@code LayoutChanged} 로 화면에 온다.
 */
final class PaneOpener implements SidebarOpener {
    private static final System.Logger LOG = System.getLogger(PaneOpener.class.getName());

    private final OpenPaneUseCase useCase;
    private final Executor background;
    private final Executor ui;
    private final Consumer<List<String>> notify;

    PaneOpener(OpenPaneUseCase useCase, Executor background, Executor ui, Consumer<List<String>> notify) {
        this.useCase = Objects.requireNonNull(useCase, "useCase");
        this.background = Objects.requireNonNull(background, "background");
        this.ui = Objects.requireNonNull(ui, "ui");
        this.notify = Objects.requireNonNull(notify, "notify");
    }

    @Override
    public void open(WorkspacePath path, Optional<ProfileId> profile, Optional<OpenPlacement> placement) {
        run(() -> useCase.open(path, profile, placement));
    }

    @Override
    public void openHome(ShellEnvironment env) {
        run(() -> useCase.openHome(env, Optional.empty()));
    }

    @Override
    public void choices(WorkspacePath path, Consumer<OpenChoices> onEdt) {
        background.execute(() -> {
            try {
                OpenChoices choices = useCase.choices(path);
                ui.execute(() -> onEdt.accept(choices));
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "열기 메뉴 후보를 구하지 못했다", e);
            }
        });
    }

    private void run(Supplier<CompletionStage<Result<PaneSpec, OpenError>>> call) {
        background.execute(() -> {
            try {
                call.get().whenComplete((result, failure) -> {
                    if (failure != null) {
                        LOG.log(System.Logger.Level.WARNING, "창을 열지 못했다", failure);
                        return;
                    }
                    result.error().ifPresent(error -> {
                        String text = NoticeText.DEFAULT.of(error);
                        ui.execute(() -> notify.accept(List.of(text)));
                    });
                });
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "창을 열지 못했다", e);
            }
        });
    }
}
