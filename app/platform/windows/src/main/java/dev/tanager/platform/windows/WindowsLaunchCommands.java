package dev.tanager.platform.windows;

import dev.tanager.application.port.out.LaunchCommand;
import dev.tanager.application.port.out.LaunchCommand.EnvNames;
import dev.tanager.domain.history.ShellEnvironment;
import dev.tanager.domain.session.LaunchTemplate;
import dev.tanager.domain.session.ShellProfile;
import dev.tanager.domain.session.WorkingDir;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Windows 의 세 셸(WSL bash · PowerShell 5.1 · cmd)을 작업 경로에서 띄우는 PTY 명령. 순수 계산이다. */
final class WindowsLaunchCommands {
    // wsl.exe 의 --cd ~ 는 리눅스 사용자의 홈으로 들어간다 — 홈을 미리 조회하지 않는다(ADR-0009)
    private static final String WSL_HOME = "~";
    // wsl.exe 도 Windows 프로세스라 앱 환경을 Windows 규칙(대소문자 무시)으로 물려받는다
    private static final EnvNames WINDOWS_NAMES = EnvNames.CASE_INSENSITIVE;

    private WindowsLaunchCommands() {}

    /** {@code dir} 이 {@link WorkingDir.Home} 이면 WSL 은 {@code ~}, Windows 셸은 {@code userProfile} 에서 띄운다. */
    static LaunchCommand of(ShellProfile profile, WorkingDir dir, String userProfile) {
        if (dir instanceof WorkingDir.At at && !profile.env().equals(at.path().env())) {
            throw new IllegalArgumentException(
                    "프로파일 환경 " + profile.env() + " 과 경로 환경 " + at.path().env() + " 이 다르다");
        }
        LaunchTemplate template = profile.launch();
        return switch (profile.env()) {
            // 리눅스 경로는 Windows 작업 디렉터리로 줄 수 없다 — wsl.exe 의 --cd 로 넘긴다(ADR-0007)
            case ShellEnvironment.Wsl _ ->
                new LaunchCommand(wslArgv(template, pathOr(dir, WSL_HOME)), Optional.empty(), Map.of(), WINDOWS_NAMES);
            case ShellEnvironment.Windows _ ->
                new LaunchCommand(
                        templateArgv(template), Optional.of(pathOr(dir, userProfile)), Map.of(), WINDOWS_NAMES);
        };
    }

    private static String pathOr(WorkingDir dir, String home) {
        return switch (dir) {
            case WorkingDir.Home _ -> home;
            case WorkingDir.At at -> at.path().path();
        };
    }

    private static List<String> wslArgv(LaunchTemplate template, String cd) {
        List<String> argv = templateArgv(template);
        argv.addAll(List.of("--cd", cd, "--", "bash", "-i"));
        return argv;
    }

    private static List<String> templateArgv(LaunchTemplate template) {
        List<String> argv = new ArrayList<>();
        argv.add(template.executable());
        argv.addAll(template.args());
        return argv;
    }
}
