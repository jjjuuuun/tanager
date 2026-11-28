package tanager.application.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import tanager.application.port.in.AppEvent;
import tanager.application.port.in.ListProfilesUseCase;
import tanager.application.port.in.RecordVisitUseCase;
import tanager.application.port.in.RememberDefaultProfileUseCase;
import tanager.application.port.in.StartFailure;
import tanager.application.port.in.StartNotice;
import tanager.application.port.in.StartRequest;
import tanager.application.port.in.StartSessionUseCase;
import tanager.application.port.in.StartedSession;
import tanager.application.port.out.AppConfig;
import tanager.application.port.out.ConfigRepository;
import tanager.application.port.out.ConfigWarning;
import tanager.application.port.out.Integration;
import tanager.application.port.out.LaunchError;
import tanager.application.port.out.PlatformModule;
import tanager.application.port.out.PtyLauncher;
import tanager.application.port.out.TerminalSize;
import tanager.application.port.out.TerminalStream;
import tanager.application.state.AppState;
import tanager.application.state.StateActor;
import tanager.application.state.Transition;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.session.IntegrationMode;
import tanager.domain.session.LiveSession;
import tanager.domain.session.LiveSessions;
import tanager.domain.session.ProfileResolution;
import tanager.domain.session.SessionState;
import tanager.domain.session.ShellProfile;
import tanager.domain.session.TerminalSession;
import tanager.domain.session.WorkingDir;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.Result;

/**
 * 프로파일 · 시작 경로를 정해 셸을 띄운다(FR-SES-02 · 04, ADR-0009). 띄울 셸을 정할 수 없으면 다른 셸로 바꾸지 않고 사용자에게 고르게
 * 한다. 설정은 사용자가 기억을 켜고 고른 셸이 떴을 때만 저장한다.
 *
 * <p>띄운 셸은 돌려주기 전에 창의 세션으로 등록하고 cwd 추적을 붙인다(FR-SES-03 · FR-HIS-01, ADR-0015). 받은 쪽이 {@code
 * stream.start()} 하기 전이라 첫 바이트의 보고도 놓치지 않는다.
 */
public final class SessionStarter implements StartSessionUseCase {
    private static final String HOME_SHORTHAND = "~";
    // WSL 경로 존재 확인을 기다리는 한도(#179)
    private static final Duration PROBE_BUDGET = Duration.ofSeconds(1);

    private final ListProfilesUseCase profiles;
    private final ConfigRepository config;
    private final PlatformModule platform;
    private final IntegrationStatus integration;
    private final PtyLauncher launcher;
    private final RememberDefaultProfileUseCase memory;
    private final StateActor state;
    private final RecordVisitUseCase visits;
    private final StartupCommands commands;

    public SessionStarter(
            ListProfilesUseCase profiles,
            ConfigRepository config,
            PlatformModule platform,
            IntegrationStatus integration,
            PtyLauncher launcher,
            RememberDefaultProfileUseCase memory,
            StateActor state,
            RecordVisitUseCase visits,
            StartupCommands commands) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.config = Objects.requireNonNull(config, "config");
        this.platform = Objects.requireNonNull(platform, "platform");
        this.integration = Objects.requireNonNull(integration, "integration");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.state = Objects.requireNonNull(state, "state");
        this.visits = Objects.requireNonNull(visits, "visits");
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    @Override
    public Result<StartedSession, StartFailure> start(StartRequest request) {
        Objects.requireNonNull(request, "request");
        List<StartNotice> notices = new ArrayList<>();
        List<ShellProfile> available = profiles.list();
        if (available.isEmpty()) {
            return Result.err(new StartFailure.NoShellAvailable());
        }
        AppConfig settings = loadConfig(notices);
        return switch (ProfileResolution.resolve(request.profile().or(settings::defaultProfile), available)) {
            case ProfileResolution.Found found -> startFound(found.profile(), request, settings, notices);
            case ProfileResolution.Choose choose -> Result.err(new StartFailure.ChoiceRequired(choose, notices));
            case ProfileResolution.NoShell _ -> Result.err(new StartFailure.NoShellAvailable());
        };
    }

    private Result<StartedSession, StartFailure> startFound(
            ShellProfile profile, StartRequest request, AppConfig settings, List<StartNotice> notices) {
        WorkingDir dir = request.dir().orElseGet(() -> configuredDir(profile, settings, notices));
        Result<StartedSession, StartFailure> started = TerminalSession.start(profile, dir)
                .fold(
                        starting -> launchTracked(request.pane(), starting, request.size(), notices, true),
                        error -> Result.err(new StartFailure.Rejected(error)));
        if (request.rememberAsDefault() && started.isOk()) {
            return started.map(session -> remembered(session, profile));
        }
        return started;
    }

    // 띄우지 못한 셸은 기억하지 않는다. 저장이 실패해도 셸은 그대로 쓰고 마지막 알림으로 알린다.
    private StartedSession remembered(StartedSession session, ShellProfile profile) {
        return memory.remember(profile.id()).fold(saved -> session, error -> {
            List<StartNotice> notices = new ArrayList<>(session.notices());
            notices.add(new StartNotice.DefaultNotSaved(profile.id(), error));
            return new StartedSession(session.session(), session.stream(), session.exited(), notices);
        });
    }

    @Override
    public Result<StartedSession, StartFailure> restart(PaneId pane, TerminalSession exited, TerminalSize size) {
        Objects.requireNonNull(pane, "pane");
        Objects.requireNonNull(exited, "exited");
        Objects.requireNonNull(size, "size");
        return latestOf(pane, exited)
                .restart()
                .fold(
                        starting -> launchTracked(pane, starting, size, new ArrayList<>(), false),
                        error -> Result.err(new StartFailure.Rejected(error)));
    }

    /**
     * UI 가 가진 {@code exited} 는 처음 Running 에서 만들어져 시작 경로를 가리킨다. 상태에 등록된 그 창의 같은 셸이 이미 끝나 있으면,
     * 셸이 마지막으로 알린 경로를 가진 그쪽에서 다시 띄운다(#104). 아직 종료가 상태에 반영되지 않았으면 받은 값을 그대로 쓴다 — 지금까지와
     * 같은 동작으로 물러선다.
     */
    private TerminalSession latestOf(PaneId pane, TerminalSession exited) {
        return Optional.ofNullable(state.snapshot().sessions().byPane().get(pane))
                .map(LiveSession::session)
                .filter(live -> live.state() instanceof SessionState.Exited)
                .filter(live -> live.profile().equals(exited.profile()))
                .orElse(exited);
    }

    private AppConfig loadConfig(List<StartNotice> notices) {
        return config.load()
                .fold(
                        loaded -> {
                            for (ConfigWarning warning : loaded.warnings()) {
                                notices.add(new StartNotice.ConfigWarned(warning));
                            }
                            return loaded.config();
                        },
                        error -> {
                            notices.add(new StartNotice.ConfigUnreadable(error));
                            return AppConfig.defaults();
                        });
    }

    private static WorkingDir configuredDir(ShellProfile profile, AppConfig settings, List<StartNotice> notices) {
        Optional<String> raw = settings.startDirOf(profile.id());
        if (raw.isEmpty() || raw.get().equals(HOME_SHORTHAND)) {
            return new WorkingDir.Home();
        }
        return WorkspacePath.of(profile.env(), raw.get()).<WorkingDir>fold(WorkingDir.At::new, error -> {
            notices.add(new StartNotice.StartDirInvalid(raw.get()));
            return new WorkingDir.Home();
        });
    }

    // 설치는 프로세스에서 한 번이다 — 처음이면 여기서 끝날 때까지 기다린다(start · restart 는 EDT 밖에서 불린다, ADR-0014).
    // 시작 명령(#216)은 창의 첫 셸(fresh)에만 단다 — 재시작한 셸에서 다시 실행하지 않는다
    private Result<StartedSession, StartFailure> launchTracked(
            PaneId pane, TerminalSession starting, TerminalSize size, List<StartNotice> notices, boolean fresh) {
        Integration installed = integration.await();
        return launch(starting, size, installed, notices)
                .map(launched -> tracked(pane, launched, installed, notices, fresh));
    }

    /** 돌려주기 전에 (1) 창의 세션 등록 (2) 시작 명령 · cwd 추적 (3) 종료 등록 순으로 붙인다. */
    private StartedSession tracked(
            PaneId pane, Launched launched, Integration integration, List<StartNotice> notices, boolean fresh) {
        TerminalSession running = launched.running();
        TerminalStream stream = launched.stream();
        state.submit(current -> sessionsChanged(current, current.sessions().launched(pane, running)));
        boolean reportsCwd =
                running.profile().integration() != IntegrationMode.NONE && integration instanceof Integration.Installed;
        RecordVisitUseCase recorder = fresh ? commands.launched(pane, running, stream, reportsCwd, visits) : visits;
        followCwd(pane, running, stream, integration, notices, recorder);
        long pid = stream.pid();
        stream.onExit().thenAccept(code -> state.submit(current -> exited(current, pane, pid, code)));
        CompletionStage<TerminalSession> exited = stream.onExit().thenApply(code -> expect(running.exited(code)));
        return new StartedSession(running, stream, exited, notices);
    }

    /**
     * 통합이 있으면 출력을 구독하고, 없으면 구독 대신 시작 경로를 한 번 기록하고 알린다. 홈으로 대신 띄웠으면 시작 경로를 모르니 기록하지
     * 않는다. 통합 모드가 NONE 인 셸은 추적하지 않는다.
     */
    private void followCwd(
            PaneId pane,
            TerminalSession running,
            TerminalStream stream,
            Integration integration,
            List<StartNotice> notices,
            RecordVisitUseCase recorder) {
        IntegrationMode mode = running.profile().integration();
        if (mode == IntegrationMode.NONE) {
            return;
        }
        switch (integration) {
            case Integration.Installed _ ->
                stream.subscribe(new CwdTracker(pane, running.profile().env(), mode, recorder));
            case Integration.Unavailable(var problem) -> {
                notices.add(new StartNotice.IntegrationUnavailable(problem));
                running.knownCwd().ifPresent(start -> visits.record(pane, start));
            }
        }
    }

    // 재시작으로 같은 창에 새 셸이 먼저 등록됐으면 늦게 온 옛 셸의 종료는 버린다
    private static Transition<Boolean> exited(AppState current, PaneId pane, long pid, int code) {
        boolean same = Optional.ofNullable(current.sessions().byPane().get(pane))
                .filter(live -> live.session().state() instanceof SessionState.Running r && r.pid() == pid)
                .isPresent();
        return same
                ? sessionsChanged(current, current.sessions().exited(pane, code))
                : new Transition<>(current, List.of(), false);
    }

    private static Transition<Boolean> sessionsChanged(AppState current, LiveSessions next) {
        return new Transition<>(current.withSessions(next), List.of(new AppEvent.SessionsChanged(next)), true);
    }

    /** 떴지만 아직 추적을 붙이지 않은 셸. */
    private record Launched(TerminalSession running, TerminalStream stream) {}

    // Windows 경로는 미리 보지 않는다 — 기동이 WorkingDirectoryNotFound 를 주면 홈에서 한 번 더 띄운다(ADR-0009).
    // WSL 은 없는 --cd 를 받아도 오류 없이 / 에서 뜨므로(#53) 기동 전에 확인한다(missingInWsl).
    // 홈으로 다시 띄울 때도 같은 설치 결과를 쓴다 — 설치는 프로세스에서 한 번이다(ADR-0014)
    // launchCommand 의 IllegalArgumentException(환경 불일치 · #107 안전하지 않은 인자)은 잡지 않는다. 경로는 WorkspacePath 가,
    // 환경은 세션 생성이 먼저 맞추므로 도달할 수 없는 버그 경로다 — LaunchFailed 로 바꾸면 버그가 평범한 기동 실패로 숨는다
    private Result<Launched, StartFailure> launch(
            TerminalSession starting, TerminalSize size, Integration installed, List<StartNotice> notices) {
        WorkingDir dir = startingDir(starting);
        if (dir instanceof WorkingDir.At at && missingInWsl(at.path())) {
            return launchAtHome(starting, at, size, installed, notices);
        }
        Result<TerminalStream, LaunchError> launched =
                launcher.launch(platform.launchCommand(starting.profile(), dir, installed), size);
        if (dir instanceof WorkingDir.At at && isMissingDirectory(launched)) {
            return launchAtHome(starting, at, size, installed, notices);
        }
        return launched.fold(
                stream -> Result.ok(new Launched(expect(starting.launched(stream.pid())), stream)),
                error -> Result.err(failed(starting, error, notices)));
    }

    private Result<Launched, StartFailure> launchAtHome(
            TerminalSession starting,
            WorkingDir.At missing,
            TerminalSize size,
            Integration installed,
            List<StartNotice> notices) {
        notices.add(new StartNotice.MissingDirectory(missing.path()));
        TerminalSession atHome = expect(TerminalSession.start(starting.profile(), new WorkingDir.Home()));
        return launch(atHome, size, installed, notices);
    }

    /**
     * WSL 경로가 없다고 확인됐다. 확인이 {@link #PROBE_BUDGET} 을 넘기면(모름) 확인 없이 그 경로로 띄운다 — VM 이 깨어나는 동안 창을 오래
     * 막지 않으려는 것이다(#179).
     */
    private boolean missingInWsl(WorkspacePath path) {
        return path.env() instanceof ShellEnvironment.Wsl
                && platform.directories().isDirectory(path, PROBE_BUDGET).equals(Optional.of(false));
    }

    private static boolean isMissingDirectory(Result<TerminalStream, LaunchError> launched) {
        return launched.error()
                .filter(LaunchError.WorkingDirectoryNotFound.class::isInstance)
                .isPresent();
    }

    private static StartFailure failed(TerminalSession starting, LaunchError error, List<StartNotice> notices) {
        return new StartFailure.LaunchFailed(expect(starting.launchFailed(error.describe())), error, notices);
    }

    private static WorkingDir startingDir(TerminalSession session) {
        return switch (session.state()) {
            case SessionState.Starting s -> s.dir();
            case SessionState.Running _, SessionState.Exited _, SessionState.Failed _ ->
                throw new IllegalStateException("Starting 이 아닌 세션을 띄우려 했다: " + session.state());
        };
    }

    // 여기서 부르는 전이는 앞 단계가 상태를 보장해 실패하지 않는다. 실패하면 버그다.
    private static <T, E> T expect(Result<T, E> result) {
        return result.fold(value -> value, error -> {
            throw new IllegalStateException("세션 전이가 거부됐다: " + error);
        });
    }
}
