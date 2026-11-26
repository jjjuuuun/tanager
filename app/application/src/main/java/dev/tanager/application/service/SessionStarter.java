package dev.tanager.application.service;

import dev.tanager.application.port.in.ListProfilesUseCase;
import dev.tanager.application.port.in.RememberDefaultProfileUseCase;
import dev.tanager.application.port.in.StartFailure;
import dev.tanager.application.port.in.StartNotice;
import dev.tanager.application.port.in.StartRequest;
import dev.tanager.application.port.in.StartSessionUseCase;
import dev.tanager.application.port.in.StartedSession;
import dev.tanager.application.port.out.AppConfig;
import dev.tanager.application.port.out.ConfigRepository;
import dev.tanager.application.port.out.ConfigWarning;
import dev.tanager.application.port.out.LaunchError;
import dev.tanager.application.port.out.PlatformModule;
import dev.tanager.application.port.out.PtyLauncher;
import dev.tanager.application.port.out.TerminalSize;
import dev.tanager.application.port.out.TerminalStream;
import dev.tanager.domain.history.WorkspacePath;
import dev.tanager.domain.session.ProfileResolution;
import dev.tanager.domain.session.SessionState;
import dev.tanager.domain.session.ShellProfile;
import dev.tanager.domain.session.TerminalSession;
import dev.tanager.domain.session.WorkingDir;
import dev.tanager.domain.shared.Result;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * 프로파일 · 시작 경로를 정해 셸을 띄운다(FR-SES-02 · 04, ADR-0009). 띄울 셸을 정할 수 없으면 다른 셸로 바꾸지 않고 사용자에게 고르게
 * 한다. 설정은 사용자가 기억을 켜고 고른 셸이 떴을 때만 저장한다.
 */
public final class SessionStarter implements StartSessionUseCase {
    private static final String HOME_SHORTHAND = "~";

    private final ListProfilesUseCase profiles;
    private final ConfigRepository config;
    private final PlatformModule platform;
    private final PtyLauncher launcher;
    private final RememberDefaultProfileUseCase memory;

    public SessionStarter(
            ListProfilesUseCase profiles,
            ConfigRepository config,
            PlatformModule platform,
            PtyLauncher launcher,
            RememberDefaultProfileUseCase memory) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.config = Objects.requireNonNull(config, "config");
        this.platform = Objects.requireNonNull(platform, "platform");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.memory = Objects.requireNonNull(memory, "memory");
    }

    @Override
    public Result<StartedSession, StartFailure> start(StartRequest request) {
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
                        starting -> launch(starting, request.size(), notices),
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
    public Result<StartedSession, StartFailure> restart(TerminalSession exited, TerminalSize size) {
        Objects.requireNonNull(exited, "exited");
        Objects.requireNonNull(size, "size");
        return exited.restart()
                .fold(
                        starting -> launch(starting, size, new ArrayList<>()),
                        error -> Result.err(new StartFailure.Rejected(error)));
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

    // 경로가 있는지 미리 보지 않는다 — 기동이 WorkingDirectoryNotFound 를 주면 홈에서 한 번 더 띄운다(ADR-0009).
    private Result<StartedSession, StartFailure> launch(
            TerminalSession starting, TerminalSize size, List<StartNotice> notices) {
        WorkingDir dir = startingDir(starting);
        Result<TerminalStream, LaunchError> launched =
                launcher.launch(platform.launchCommand(starting.profile(), dir), size);
        if (dir instanceof WorkingDir.At at && isMissingDirectory(launched)) {
            notices.add(new StartNotice.StartDirMissing(at.path()));
            TerminalSession atHome = expect(TerminalSession.start(starting.profile(), new WorkingDir.Home()));
            return launch(atHome, size, notices);
        }
        return launched.fold(
                stream -> Result.ok(running(starting, stream, notices)),
                error -> Result.err(failed(starting, error, notices)));
    }

    private static boolean isMissingDirectory(Result<TerminalStream, LaunchError> launched) {
        return launched.error()
                .filter(LaunchError.WorkingDirectoryNotFound.class::isInstance)
                .isPresent();
    }

    private static StartedSession running(TerminalSession starting, TerminalStream stream, List<StartNotice> notices) {
        TerminalSession running = expect(starting.launched(stream.pid()));
        CompletionStage<TerminalSession> exited = stream.onExit().thenApply(code -> expect(running.exited(code)));
        return new StartedSession(running, stream, exited, notices);
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
