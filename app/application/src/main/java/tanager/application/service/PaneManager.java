package tanager.application.service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import tanager.application.port.in.AppEvent;
import tanager.application.port.in.ClosePaneUseCase;
import tanager.application.port.in.FocusPaneUseCase;
import tanager.application.port.in.ListProfilesUseCase;
import tanager.application.port.in.OpenError;
import tanager.application.port.in.OpenPaneUseCase;
import tanager.application.port.out.AppConfig;
import tanager.application.port.out.ConfigRepository;
import tanager.application.port.out.LoadedConfig;
import tanager.application.state.StateActor;
import tanager.application.state.Transition;
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
    private final StateActor state;

    public PaneManager(ListProfilesUseCase profiles, ConfigRepository config, StateActor state) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.config = Objects.requireNonNull(config, "config");
        this.state = Objects.requireNonNull(state, "state");
    }

    @Override
    public CompletionStage<Result<PaneSpec, OpenError>> open(WorkspacePath path, Optional<ProfileId> profile) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(profile, "profile");
        // 설정을 읽지 못하면 기본값으로 연다 — 그 경고는 세션 시작이 알린다
        AppConfig settings = config.load().value().map(LoadedConfig::config).orElseGet(AppConfig::defaults);
        Result<ShellProfile, OpenError> chosen = choose(path, profile, settings, profiles.list());
        Orientation orientation = switch (settings.openPlacement()) {
            case RIGHT -> Orientation.ROW;
            case DOWN -> Orientation.COLUMN;
        };
        return state.submit(current -> chosen.fold(
                shell -> {
                    Workspace.Opened opened = current.workspace().openBeside(orientation, shell.id(), path);
                    Workspace next = opened.workspace();
                    return new Transition<>(
                            current.withWorkspace(next),
                            List.of(new AppEvent.LayoutChanged(next)),
                            Result.<PaneSpec, OpenError>ok(
                                    next.spec(opened.pane()).orElseThrow()));
                },
                error -> new Transition<>(current, List.of(), Result.<PaneSpec, OpenError>err(error))));
    }

    private static Result<ShellProfile, OpenError> choose(
            WorkspacePath path, Optional<ProfileId> wanted, AppConfig settings, List<ShellProfile> available) {
        if (wanted.isPresent()) {
            ProfileId id = wanted.get();
            Optional<ShellProfile> found =
                    available.stream().filter(p -> p.id().equals(id)).findFirst();
            if (found.isEmpty()) {
                return Result.err(new OpenError.UnknownProfile(id));
            }
            if (!found.get().env().equals(path.env())) {
                return Result.err(new OpenError.EnvironmentMismatch(id, path));
            }
            return Result.ok(found.get());
        }
        return ProfileResolution.forEnvironment(path.env(), settings.defaultProfile(), available)
                .<Result<ShellProfile, OpenError>>map(Result::ok)
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
