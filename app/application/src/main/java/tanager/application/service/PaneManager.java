package tanager.application.service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import tanager.application.port.in.AppEvent;
import tanager.application.port.in.ClosePaneUseCase;
import tanager.application.port.in.FocusPaneUseCase;
import tanager.application.port.in.ListProfilesUseCase;
import tanager.application.port.in.MovePaneUseCase;
import tanager.application.port.in.OpenChoices;
import tanager.application.port.in.OpenError;
import tanager.application.port.in.OpenPaneUseCase;
import tanager.application.port.in.ResizePaneUseCase;
import tanager.application.port.in.SplitPaneUseCase;
import tanager.application.port.in.SwapPaneUseCase;
import tanager.application.port.in.ZoomPaneUseCase;
import tanager.application.port.out.AppConfig;
import tanager.application.port.out.ConfigRepository;
import tanager.application.port.out.LoadedConfig;
import tanager.application.port.out.OpenPlacement;
import tanager.application.port.out.PathTranslation;
import tanager.application.state.AppState;
import tanager.application.state.StateActor;
import tanager.application.state.Transition;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.layout.Direction;
import tanager.domain.layout.DropZone;
import tanager.domain.layout.LayoutError;
import tanager.domain.layout.Orientation;
import tanager.domain.layout.Ratio;
import tanager.domain.layout.Rect;
import tanager.domain.layout.SplitPath;
import tanager.domain.session.LiveSession;
import tanager.domain.session.ProfileId;
import tanager.domain.session.ProfileResolution;
import tanager.domain.session.ShellProfile;
import tanager.domain.session.TerminalSession;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.Result;
import tanager.domain.workspace.PaneSpec;
import tanager.domain.workspace.Workspace;

/**
 * 창 열기 · 닫기 · 포커스(#88)와 분할 · 크기 · 방향 포커스 · 줌 · 교환 · 옮기기(#147). 작업 공간은 {@link StateActor} 에서만 바뀐다. 레이아웃
 * 연산은 작업 공간이 실제로 바뀔 때만 {@code LayoutChanged} 를 한 번 내고, 실패 · 바뀐 게 없음이면 상태도 이벤트도 그대로다.
 */
public final class PaneManager
        implements OpenPaneUseCase,
                ClosePaneUseCase,
                FocusPaneUseCase,
                SplitPaneUseCase,
                ResizePaneUseCase,
                ZoomPaneUseCase,
                SwapPaneUseCase,
                MovePaneUseCase {
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
        return changeLayout(workspace -> workspace.focus(pane));
    }

    @Override
    public CompletionStage<Result<Workspace, LayoutError>> focus(Direction direction, Rect bounds, int gap) {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(bounds, "bounds");
        return changeLayout(workspace -> workspace.focus(direction, bounds, gap));
    }

    @Override
    public CompletionStage<Result<Workspace, LayoutError>> split(Orientation orientation) {
        Objects.requireNonNull(orientation, "orientation");
        return state.submit(current -> {
            PaneSpec like = besideFocused(current);
            Workspace next = current.workspace()
                    .split(orientation, like.profile(), like.dir())
                    .workspace();
            return changed(current, next);
        });
    }

    @Override
    public CompletionStage<Result<Workspace, LayoutError>> resize(Direction direction) {
        Objects.requireNonNull(direction, "direction");
        return changeLayout(workspace -> workspace.resize(direction));
    }

    @Override
    public CompletionStage<Result<Workspace, LayoutError>> resizeTo(SplitPath path, Ratio ratio) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(ratio, "ratio");
        return changeLayout(workspace -> workspace.resizeTo(path, ratio));
    }

    @Override
    public CompletionStage<Result<Workspace, LayoutError>> toggle() {
        return changeLayout(workspace -> Result.ok(workspace.toggleZoom()));
    }

    @Override
    public CompletionStage<Result<Workspace, LayoutError>> swap(Direction direction, Rect bounds, int gap) {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(bounds, "bounds");
        return changeLayout(workspace -> workspace.swap(direction, bounds, gap));
    }

    @Override
    public CompletionStage<Result<Workspace, LayoutError>> move(PaneId pane, DropZone zone) {
        Objects.requireNonNull(pane, "pane");
        Objects.requireNonNull(zone, "zone");
        return changeLayout(workspace -> workspace.move(pane, zone));
    }

    /**
     * 포커스 창 옆에 열 창이 따를 (프로파일, 경로). 세션이 있으면 그 셸과 현재 경로 — 경로와 프로파일이 같은 세션에서 와야 환경이 어긋나지 않는다.
     * 없으면 그 창의 명세 그대로.
     */
    private static PaneSpec besideFocused(AppState current) {
        PaneId focused = current.workspace().tab().focused();
        Optional<LiveSession> live =
                Optional.ofNullable(current.sessions().byPane().get(focused));
        if (live.isPresent()) {
            TerminalSession session = live.get().session();
            return new PaneSpec(focused, Optional.of(session.profile().id()), session.knownCwd());
        }
        // 명세가 있는 창 = 트리의 창(Workspace 불변식)
        return current.workspace().spec(focused).orElseThrow();
    }

    private CompletionStage<Result<Workspace, LayoutError>> changeLayout(
            Function<Workspace, Result<Workspace, LayoutError>> operation) {
        return state.submit(current -> operation
                .apply(current.workspace())
                .fold(
                        next -> changed(current, next),
                        error -> new Transition<>(current, List.of(), Result.err(error))));
    }

    // 실제로 바뀌었을 때만 상태를 바꾸고 LayoutChanged 를 한 번 낸다
    private static Transition<Result<Workspace, LayoutError>> changed(AppState current, Workspace next) {
        Result<Workspace, LayoutError> reply = Result.ok(next);
        if (next.equals(current.workspace())) {
            return new Transition<>(current, List.of(), reply);
        }
        return new Transition<>(current.withWorkspace(next), List.of(new AppEvent.LayoutChanged(next)), reply);
    }
}
