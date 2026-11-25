package dev.tanager.application.service;

import dev.tanager.application.port.in.ListProfilesUseCase;
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
import dev.tanager.domain.session.DefaultProfile;
import dev.tanager.domain.session.ProfileChoice;
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
 * 프로파일 · 시작 경로를 정해 셸을 띄운다(FR-SES-02 · 04, ADR-0009). 설정은 읽기만 하고 저장하지 않는다 — 기본값으로 대체한
 * 결정은 이번 실행에서만 쓰고 알림으로 알린다.
 */
public final class SessionStarter implements StartSessionUseCase {
    private static final String HOME_SHORTHAND = "~";

    private final ListProfilesUseCase profiles;
    private final ConfigRepository config;
    private final PlatformModule platform;
    private final PtyLauncher launcher;

    public SessionStarter(
            ListProfilesUseCase profiles, ConfigRepository config, PlatformModule platform, PtyLauncher launcher) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.config = Objects.requireNonNull(config, "config");
        this.platform = Objects.requireNonNull(platform, "platform");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
    }

    @Override
    public Result<StartedSession, StartFailure> start(StartRequest request) {
        List<StartNotice> notices = new ArrayList<>();
        List<ShellProfile> available = profiles.list();
        if (available.isEmpty()) {
            return Result.err(new StartFailure.NoShellAvailable());
        }
        AppConfig settings = loadConfig(notices);
        ProfileChoice choice = DefaultProfile.choose(request.profile().or(settings::defaultProfile), available)
                .orElseThrow(() -> new IllegalStateException("비지 않은 목록에서 프로파일을 고르지 못했다"));
        ShellProfile profile = choice.profile();
        choice.replaced().ifPresent(replaced -> notices.add(new StartNotice.ProfileReplaced(replaced, profile.id())));
        WorkingDir dir = request.dir().orElseGet(() -> configuredDir(profile, settings, notices));
        return TerminalSession.start(profile, dir)
                .fold(
                        starting -> launch(starting, request.size(), notices),
                        error -> Result.err(new StartFailure.Rejected(error)));
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
