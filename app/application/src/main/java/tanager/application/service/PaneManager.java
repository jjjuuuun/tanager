package tanager.application.service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import tanager.application.port.in.AppEvent;
import tanager.application.port.in.ClosePaneUseCase;
import tanager.application.port.in.FocusPaneUseCase;
import tanager.application.port.in.ListProfilesUseCase;
import tanager.application.port.in.OpenChoices;
import tanager.application.port.in.OpenError;
import tanager.application.port.in.OpenPaneUseCase;
import tanager.application.port.out.AppConfig;
import tanager.application.port.out.ConfigRepository;
import tanager.application.port.out.LoadedConfig;
import tanager.application.port.out.OpenPlacement;
import tanager.application.port.out.PathTranslation;
import tanager.application.state.StateActor;
import tanager.application.state.Transition;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.layout.LayoutError;
import tanager.domain.layout.Orientation;
import tanager.domain.session.ProfileId;
import tanager.domain.session.ProfileResolution;
import tanager.domain.session.ShellProfile;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.Result;
import tanager.domain.workspace.PaneSpec;
import tanager.domain.workspace.Workspace;

/** 창 열기 · 닫기 · 포커스(#88). 작업 공간은 {@link StateActor} 에서만 바뀐다. */
public final class PaneManager implements OpenPaneUseCase, ClosePaneUseCase, FocusPaneUseCase {
    private final ListProfilesUseCase profiles;
    private final ConfigRepository config;
    private final PathTranslation paths;
    private final StateActor state;

    public PaneManager(ListProfilesUseCase profiles, ConfigRepository config, PathTranslation paths, StateActor state) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.config = Objects.requireNonNull(config, "config");
        this.paths = Objects.requireNonNull(paths, "paths");
        this.state = Objects.requireNonNull(state, "state");
    }

    /** 띄울 셸과 그 셸 환경의 시작 경로(비면 그 셸의 시작 경로 → 홈). */
    private record Choice(ShellProfile shell, Optional<WorkspacePath> dir) {}

    @Override
    public CompletionStage<Result<PaneSpec, OpenError>> open(
            WorkspacePath path, Optional<ProfileId> profile, Optional<OpenPlacement> placement) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(placement, "placement");
        AppConfig settings = settings();
        return openWith(choose(path, profile, settings, profiles.list()), placement.orElse(settings.openPlacement()));
    }

    @Override
    public CompletionStage<Result<PaneSpec, OpenError>> openHome(
            ShellEnvironment env, Optional<OpenPlacement> placement) {
        Objects.requireNonNull(env, "env");
        Objects.requireNonNull(placement, "placement");
        AppConfig settings = settings();
        Result<Choice, OpenError> chosen = ProfileResolution.forEnvironment(
                        env, settings.defaultProfile(), profiles.list())
                .<Result<Choice, OpenError>>map(shell -> Result.ok(new Choice(shell, Optional.empty())))
                .orElseGet(() -> Result.err(new OpenError.NoProfileForEnvironment(env)));
        return openWith(chosen, placement.orElse(settings.openPlacement()));
    }

    @Override
    public OpenChoices choices(WorkspacePath path) {
        Objects.requireNonNull(path, "path");
        List<ShellProfile> available = profiles.list();
        Optional<ShellProfile> primary =
                ProfileResolution.forEnvironment(path.env(), settings().defaultProfile(), available);
        List<ShellProfile> others = available.stream()
                .filter(shell -> primary.filter(shell::equals).isEmpty())
                .filter(shell -> paths.translate(path, shell.env()).isPresent())
                .toList();
        return new OpenChoices(primary, others);
    }

    // 설정을 읽지 못하면 기본값으로 연다 — 그 경고는 세션 시작이 알린다
    private AppConfig settings() {
        return config.load().value().map(LoadedConfig::config).orElseGet(AppConfig::defaults);
    }

    private CompletionStage<Result<PaneSpec, OpenError>> openWith(
            Result<Choice, OpenError> chosen, OpenPlacement placement) {
        Orientation orientation = switch (placement) {
            case RIGHT -> Orientation.ROW;
            case DOWN -> Orientation.COLUMN;
        };
        return state.submit(current -> chosen.fold(
                choice -> {
                    Workspace.Opened opened = current.workspace()
                            .openBeside(orientation, choice.shell().id(), choice.dir());
                    Workspace next = opened.workspace();
                    return new Transition<>(
                            current.withWorkspace(next),
                            List.of(new AppEvent.LayoutChanged(next)),
                            Result.<PaneSpec, OpenError>ok(
                                    next.spec(opened.pane()).orElseThrow()));
                },
                error -> new Transition<>(current, List.of(), Result.<PaneSpec, OpenError>err(error))));
    }

    private Result<Choice, OpenError> choose(
            WorkspacePath path, Optional<ProfileId> wanted, AppConfig settings, List<ShellProfile> available) {
        if (wanted.isPresent()) {
            ProfileId id = wanted.get();
            Optional<ShellProfile> found =
                    available.stream().filter(p -> p.id().equals(id)).findFirst();
            if (found.isEmpty()) {
                return Result.err(new OpenError.UnknownProfile(id));
            }
            // 다른 환경의 셸이면 그 환경의 경로로 바꿔 연다(/mnt/c/x ↔ C:\x). 바꿀 수 없으면 열지 않는다
            ShellProfile shell = found.get();
            return paths.translate(path, shell.env())
                    .<Result<Choice, OpenError>>map(dir -> Result.ok(new Choice(shell, Optional.of(dir))))
                    .orElseGet(() -> Result.err(new OpenError.EnvironmentMismatch(id, path)));
        }
        return ProfileResolution.forEnvironment(path.env(), settings.defaultProfile(), available)
                .<Result<Choice, OpenError>>map(shell -> Result.ok(new Choice(shell, Optional.of(path))))
                .orElseGet(() -> Result.err(new OpenError.NoProfileForEnvironment(path.env())));
    }

    @Override
    public CompletionStage<Result<Workspace, LayoutError>> close(PaneId pane) {
        Objects.requireNonNull(pane, "pane");
        return state.submit(current -> {
            Result<Workspace, LayoutError> closed = current.workspace().close(pane);
            if (closed.error().filter(LayoutError.LastPane.class::isInstance).isPresent()) {
                return new Transition<>(current, List.of(new AppEvent.LastPaneClosed()), closed);
            }
            return closed.fold(
                    next -> {
                        var sessions = current.sessions().removed(pane);
                        return new Transition<>(
                                current.withWorkspace(next).withSessions(sessions),
                                List.of(new AppEvent.LayoutChanged(next), new AppEvent.SessionsChanged(sessions)),
                                closed);
                    },
                    error -> new Transition<>(current, List.of(), closed));
        });
    }

    @Override
    public CompletionStage<Result<Workspace, LayoutError>> focus(PaneId pane) {
        Objects.requireNonNull(pane, "pane");
        return state.submit(current -> {
            Result<Workspace, LayoutError> focused = current.workspace().focus(pane);
            return focused.fold(
                    next -> next.equals(current.workspace())
                            ? new Transition<>(current, List.of(), focused)
                            : new Transition<>(
                                    current.withWorkspace(next), List.of(new AppEvent.LayoutChanged(next)), focused),
                    error -> new Transition<>(current, List.of(), focused));
        });
    }
}
