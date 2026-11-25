package dev.tanager.platform.windows;

import dev.tanager.domain.history.ShellEnvironment;
import dev.tanager.domain.session.IntegrationMode;
import dev.tanager.domain.session.LaunchTemplate;
import dev.tanager.domain.session.ProfileId;
import dev.tanager.domain.session.ShellKind;
import dev.tanager.domain.session.ShellProfile;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Windows 에서 띄울 수 있는 셸(WSL 배포판 · Windows PowerShell 5.1 · cmd)을 탐지한다. */
final class WindowsShellCatalog {
    private static final Logger LOG = System.getLogger(WindowsShellCatalog.class.getName());
    private static final String DEFAULT_SYSTEM_ROOT = "C:\\Windows";

    private final CommandRunner runner;
    private final Function<String, Optional<String>> env;
    private final Duration wslTimeout;

    WindowsShellCatalog(CommandRunner runner, Function<String, Optional<String>> env, Duration wslTimeout) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.env = Objects.requireNonNull(env, "env");
        this.wslTimeout = Objects.requireNonNull(wslTimeout, "wslTimeout");
    }

    /** WSL 배포판(wsl.exe 출력 순서) → powershell → cmd. WSL 탐지가 실패하면 WSL 항목만 빠진다. 불변 목록. */
    List<ShellProfile> shells() {
        String system32 = env.apply("SystemRoot").orElse(DEFAULT_SYSTEM_ROOT) + "\\System32";
        String wslExe = system32 + "\\wsl.exe";
        List<ShellProfile> profiles = new ArrayList<>();
        for (String distro : distros(wslExe)) {
            profiles.add(wsl(wslExe, distro));
        }
        profiles.add(powershell(system32));
        profiles.add(cmd(env.apply("ComSpec").orElse(system32 + "\\cmd.exe")));
        return List.copyOf(profiles);
    }

    private static ShellProfile wsl(String wslExe, String distro) {
        return new ShellProfile(
                new ProfileId("wsl:" + distro),
                distro,
                ShellKind.BASH,
                new ShellEnvironment.Wsl(distro),
                new LaunchTemplate(wslExe, List.of("-d", distro)),
                IntegrationMode.OSC7);
    }

    private static ShellProfile powershell(String system32) {
        return new ShellProfile(
                new ProfileId("powershell"),
                "Windows PowerShell",
                ShellKind.WINDOWS_POWERSHELL,
                new ShellEnvironment.Windows(),
                new LaunchTemplate(system32 + "\\WindowsPowerShell\\v1.0\\powershell.exe", List.of("-NoLogo")),
                IntegrationMode.OSC9_9);
    }

    private static ShellProfile cmd(String executable) {
        return new ShellProfile(
                new ProfileId("cmd"),
                "cmd",
                ShellKind.CMD,
                new ShellEnvironment.Windows(),
                new LaunchTemplate(executable, List.of()),
                IntegrationMode.OSC9_9);
    }

    private List<String> distros(String wslExe) {
        CommandOutcome outcome = runner.run(List.of(wslExe, "--list", "--quiet"), wslTimeout);
        return switch (outcome) {
            case CommandOutcome.Exited(int code, byte[] stdout) when code == 0 -> WslListParser.parse(stdout);
            case CommandOutcome.Exited(int code, var _) -> {
                // WSL 미설치 · 배포판 없음이 흔한 경우라 경고하지 않는다.
                LOG.log(Level.DEBUG, "wsl.exe --list 종료 코드 {0} — WSL 배포판 없음으로 처리", code);
                yield List.of();
            }
            case CommandOutcome.NotStarted(String reason) -> {
                LOG.log(Level.WARNING, "wsl.exe 를 실행하지 못함: {0}", reason);
                yield List.of();
            }
            case CommandOutcome.TimedOut(Duration after) -> {
                LOG.log(Level.WARNING, "wsl.exe --list 가 {0} 안에 끝나지 않음", after);
                yield List.of();
            }
        };
    }
}
