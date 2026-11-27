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
import tanager.application.port.in.ManageTabUseCase;
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
import tanager.domain.layout.Edge;
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
import tanager.domain.shared.TabId;
import tanager.domain.workspace.PaneSpec;
import tanager.domain.workspace.TabError;
import tanager.domain.workspace.Workspace;

/**
 * 창 열기 · 닫기 · 포커스(#88)와 분할 · 크기 · 방향 포커스 · 줌 · 교환 · 옮기기(#147), 탭(#177). 작업 공간은 {@link StateActor} 에서만 바뀐다. 레이아웃
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
                MovePaneUseCase,
                ManageTabUseCase {
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
    public CompletionStage<Result<PaneSpec, OpenError>> openSame(PaneId pane, Optional<OpenPlacement> placement) {
        Objects.requireNonNull(pane, "pane");
        Objects.requireNonNull(placement, "placement");
        OpenPlacement where = placement.orElse(settings().openPlacement());
        return state.submit(current -> {
            Optional<TerminalSession> session =
                    Optional.ofNullable(current.sessions().byPane().get(pane)).map(LiveSession::session);
            Optional<Workspace> beside = current.workspace().focus(pane).value();
            if (session.isEmpty() || beside.isEmpty()) {
                return new Transition<>(
                        current, List.of(), Result.<PaneSpec, OpenError>err(new OpenError.UnknownPane(pane)));
            }
            // 그 세션 창 옆에 연다 — 포커스 창 옆이 아니다
            return opened(
                    current,
                    openIn(
                            beside.get(),
                            where,
                            session.get().profile().id(),
                            session.get().knownCwd()));
        });
    }

    @Override
    public CompletionStage<Result<PaneSpec, OpenError>> openAt(WorkspacePath path, DropZone zone) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(zone, "zone");
        Result<Choice, OpenError> chosen = choose(path, Optional.empty(), settings(), profiles.list());
        PaneId target = zone.target();
        return state.submit(current -> chosen.fold(
                choice -> current.workspace()
                        .focus(target)
                        .value()
                        .map(beside -> opened(current, placeAt(current.workspace(), beside, zone, choice)))
                        .orElseGet(() -> new Transition<>(
                                current,
                                List.of(),
                                Result.<PaneSpec, OpenError>err(new OpenError.UnknownPane(target)))),
                error -> new Transition<>(current, List.of(), Result.<PaneSpec, OpenError>err(error))));
    }

    /**
     * 가운데면 새 탭에 연다 — 놓은 창의 포커스는 바꾸지 않는다({@code current}). 변이면 대상 창에 포커스를 옮긴 {@code beside} 에서 그 변 쪽에
     * 새 창을 연다.
     */
    private static Workspace.Opened placeAt(Workspace current, Workspace beside, DropZone zone, Choice choice) {
        return switch (zone) {
            case DropZone.Center _ -> current.newTab(Optional.of(choice.shell().id()), choice.dir());
            case DropZone.Side side -> placeBeside(beside, side.target(), side.edge(), choice);
        };
    }

    /**
     * {@code beside} 에서 {@code target} 의 {@code edge} 쪽에 새 창을 연다. 분할은 오른쪽 · 아래에만 새 창을 내므로 위 · 왼쪽은 연 뒤 그 변으로
     * 옮긴다 — 한 상태 전이 안이라 화면에는 최종 배치만 간다(LayoutChanged 한 번).
     */
    private static Workspace.Opened placeBeside(Workspace beside, PaneId target, Edge edge, Choice choice) {
        Workspace.Opened opened =
                beside.openBeside(edge.orientation(), choice.shell().id(), choice.dir());
        if (!edge.leading()) {
            return opened;
        }
        // 새 창과 대상 창은 트리에 있고 서로 다르다 — 실패하면 버그다
        Workspace moved = opened.workspace()
                .move(opened.pane(), new DropZone.Side(target, edge))
                .value()
                .orElseThrow(() -> new IllegalStateException("새 창 " + opened.pane() + " 을 " + edge + " 로 옮길 수 없다"));
        return new Workspace.Opened(moved, opened.pane());
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
        return state.submit(current -> chosen.fold(
                choice -> opened(
                        current,
                        openIn(current.workspace(), placement, choice.shell().id(), choice.dir())),
                error -> new Transition<>(current, List.of(), Result.<PaneSpec, OpenError>err(error))));
    }

    // 오른쪽 · 아래는 포커스 창 옆, 새 탭은 맨 뒤의 탭
    private static Workspace.Opened openIn(
            Workspace workspace, OpenPlacement placement, ProfileId profile, Optional<WorkspacePath> dir) {
        return switch (placement) {
            case RIGHT -> workspace.openBeside(Orientation.ROW, profile, dir);
            case DOWN -> workspace.openBeside(Orientation.COLUMN, profile, dir);
            case NEW_TAB -> workspace.newTab(Optional.of(profile), dir);
        };
    }

    private static Transition<Result<PaneSpec, OpenError>> opened(AppState current, Workspace.Opened opened) {
        Workspace next = opened.workspace();
        return new Transition<>(
                current.withWorkspace(next),
                List.of(new AppEvent.LayoutChanged(next)),
                Result.ok(next.spec(opened.pane()).orElseThrow()));
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
            return WorkspaceChange.<LayoutError>changed(current, next);
        });
    }

    @Override
    public CompletionStage<Result<Workspace, LayoutError>> split(Orientation orientation, ShellProfile shell) {
        Objects.requireNonNull(orientation, "orientation");
        Objects.requireNonNull(shell, "shell");
        return state.submit(current -> {
            // 경로 바꾸기는 순수 계산이다(PathTranslation) — 액터 안에서 해도 막히지 않는다
            Optional<WorkspacePath> dir =
                    besideFocused(current).dir().flatMap(path -> paths.translate(path, shell.env()));
            Workspace next = current.workspace()
                    .split(orientation, Optional.of(shell.id()), dir)
                    .workspace();
            return WorkspaceChange.<LayoutError>changed(current, next);
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

    @Override
    public CompletionStage<Result<Workspace, TabError>> moveToTab(PaneId pane, TabId tab) {
        return state.submit(TabCommands.moveToTab(pane, tab));
    }

    @Override
    public CompletionStage<Result<Workspace, TabError>> moveToNewTab(PaneId pane) {
        return state.submit(TabCommands.moveToNewTab(pane));
    }

    @Override
    public CompletionStage<Result<Workspace, TabError>> create() {
        return state.submit(TabCommands.create());
    }

    @Override
    public CompletionStage<Result<Workspace, TabError>> close(TabId tab) {
        return state.submit(TabCommands.close(tab));
    }

    @Override
    public CompletionStage<Result<Workspace, TabError>> rename(TabId tab, Optional<String> name) {
        return state.submit(TabCommands.rename(tab, name));
    }

    @Override
    public CompletionStage<Result<Workspace, TabError>> move(TabId tab, int toIndex) {
        return state.submit(TabCommands.move(tab, toIndex));
    }

    @Override
    public CompletionStage<Result<Workspace, TabError>> select(TabId tab) {
        return state.submit(TabCommands.select(tab));
    }

    @Override
    public CompletionStage<Result<Workspace, TabError>> selectRelative(int delta) {
        return state.submit(TabCommands.selectRelative(delta));
    }

    @Override
    public CompletionStage<Result<Workspace, TabError>> selectIndex(int index) {
        return state.submit(TabCommands.selectIndex(index));
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
        return state.submit(WorkspaceChange.of(operation));
    }
}
