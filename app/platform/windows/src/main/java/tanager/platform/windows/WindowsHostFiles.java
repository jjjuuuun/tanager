package tanager.platform.windows;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import tanager.application.port.out.HomeError;
import tanager.application.port.out.HostFiles;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.shared.Result;

/**
 * Windows 의 호스트 경로와 환경의 홈(FR-TK-04, #205). 호스트 경로는 {@link WindowsHostPaths} 의 규칙에 지금 아는 배포판별 마운트 루트를 쓴다.
 * Windows 홈은 {@code USERPROFILE}, WSL 홈은 배포판마다 처음 한 번 {@code wsl.exe} 로 묻고 앱이 끝날 때까지 기억한다. 스레드 안전.
 */
final class WindowsHostFiles implements HostFiles {
    private static final Logger LOG = System.getLogger(WindowsHostFiles.class.getName());
    private static final ShellEnvironment WINDOWS = new ShellEnvironment.Windows();
    // 깨어나는 VM 을 기다리는 시간. 넘기면 기억하지 않고 다음 호출이 다시 묻는다
    static final Duration HOME_TIMEOUT = Duration.ofSeconds(5);
    // wsl.exe 자체 메시지(배포판 없음 등)를 UTF-16LE 대신 UTF-8 로(CLAUDE.md 함정). 리눅스 프로그램 출력은 원래 바이트 그대로다
    private static final Map<String, String> WSL_ENV = Map.of("WSL_UTF8", "1");

    private final WslMountRoots mounts;
    private final CommandRunner runner;
    private final String userProfile;
    private final Map<String, WorkspacePath> wslHomes = new ConcurrentHashMap<>();

    /** {@code userProfile} 은 Windows 홈(보통 {@code USERPROFILE}). 절대 경로가 아니면 Windows 홈은 {@link HomeError.Invalid}. */
    WindowsHostFiles(WslMountRoots mounts, CommandRunner runner, String userProfile) {
        this.mounts = Objects.requireNonNull(mounts, "mounts");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.userProfile = Objects.requireNonNull(userProfile, "userProfile");
    }

    @Override
    public Optional<Path> hostPath(WorkspacePath path) {
        Objects.requireNonNull(path, "path");
        return WindowsHostPaths.hostPath(path, mounts.snapshot()).flatMap(WindowsHostFiles::toPath);
    }

    // Windows 경로 규칙은 Windows JVM 의 Path 가 마지막으로 판정한다(Windows 쪽 예약 글자 등)
    private static Optional<Path> toPath(String windowsPath) {
        try {
            return Optional.of(Path.of(windowsPath));
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    @Override
    public Result<WorkspacePath, HomeError> home(ShellEnvironment env) {
        Objects.requireNonNull(env, "env");
        return switch (env) {
            case ShellEnvironment.Windows _ ->
                WorkspacePath.of(WINDOWS, userProfile).mapError(_ -> new HomeError.Invalid(env));
            case ShellEnvironment.Wsl(String distro) -> wslHome(env, distro);
        };
    }

    private Result<WorkspacePath, HomeError> wslHome(ShellEnvironment env, String distro) {
        WorkspacePath known = wslHomes.get(distro);
        if (known != null) {
            return Result.ok(known);
        }
        // 탐지한 배포판 이름이지만 명령줄에 넣으므로 한 번 더 막는다(#107 과 같은 이유)
        if (distro.chars().anyMatch(c -> c <= ' ' || c == '"' || c == 0x7f)) {
            return Result.err(new HomeError.NotRunnable(env));
        }
        // sh -c 의 스크립트에 큰따옴표가 들어가면 wsl.exe 가 인자를 다르게 가른다(#107) — 셸 없이 printenv 로 읽는다
        List<String> command = List.of("wsl.exe", "-d", distro, "--exec", "printenv", "HOME");
        Result<WorkspacePath, HomeError> answer = switch (runner.run(command, WSL_ENV, HOME_TIMEOUT)) {
            case CommandOutcome.Exited(int code, byte[] stdout) when code == 0 -> parse(env, stdout);
            case CommandOutcome.Exited(int code, byte[] _) -> {
                LOG.log(Level.DEBUG, "WSL 홈 조회 종료 코드 {0}: {1}", code, distro);
                yield Result.err(new HomeError.NotRunnable(env));
            }
            case CommandOutcome.NotStarted(String reason) -> {
                LOG.log(Level.WARNING, "wsl.exe 를 실행하지 못함: {0}", reason);
                yield Result.err(new HomeError.NotRunnable(env));
            }
            case CommandOutcome.TimedOut(Duration after) -> {
                LOG.log(Level.WARNING, "WSL 홈 조회가 {0} 안에 끝나지 않음: {1}", after, distro);
                yield Result.err(new HomeError.TimedOut(env));
            }
        };
        answer.value().ifPresent(home -> wslHomes.putIfAbsent(distro, home));
        return answer;
    }

    private static Result<WorkspacePath, HomeError> parse(ShellEnvironment env, byte[] stdout) {
        String text = new String(stdout, StandardCharsets.UTF_8);
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) {
            end--;
        }
        // 가운데 줄바꿈 · 상대 경로 · 빈 값은 WorkspacePath 가 거부한다
        return WorkspacePath.of(env, text.substring(0, end)).mapError(_ -> new HomeError.Invalid(env));
    }
}
