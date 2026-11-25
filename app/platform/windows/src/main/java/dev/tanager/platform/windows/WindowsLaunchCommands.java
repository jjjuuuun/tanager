package dev.tanager.platform.windows;

import dev.tanager.application.port.out.LaunchCommand;
import dev.tanager.domain.history.ShellEnvironment;
import dev.tanager.domain.history.WorkspacePath;
import dev.tanager.domain.session.LaunchTemplate;
import dev.tanager.domain.session.ShellProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Windows 의 세 셸(WSL bash · PowerShell 5.1 · cmd)을 작업 경로에서 띄우는 PTY 명령. 순수 계산이다. */
final class WindowsLaunchCommands {

    private WindowsLaunchCommands() {}

    static LaunchCommand of(ShellProfile profile, WorkspacePath cwd) {
        if (!profile.env().equals(cwd.env())) {
            throw new IllegalArgumentException("프로파일 환경 " + profile.env() + " 과 경로 환경 " + cwd.env() + " 이 다르다");
        }
        LaunchTemplate template = profile.launch();
        return switch (profile.env()) {
            // 리눅스 경로는 Windows 작업 디렉터리로 줄 수 없다 — wsl.exe 의 --cd 로 넘긴다(ADR-0007)
            case ShellEnvironment.Wsl _ -> new LaunchCommand(wslArgv(template, cwd), Optional.empty(), Map.of());
            case ShellEnvironment.Windows _ ->
                new LaunchCommand(templateArgv(template), Optional.of(cwd.path()), Map.of());
        };
    }

    private static List<String> wslArgv(LaunchTemplate template, WorkspacePath cwd) {
        List<String> argv = templateArgv(template);
        argv.addAll(List.of("--cd", cwd.path(), "--", "bash", "-i"));
        return argv;
    }

    private static List<String> templateArgv(LaunchTemplate template) {
        List<String> argv = new ArrayList<>();
        argv.add(template.executable());
        argv.addAll(template.args());
        return argv;
    }
}
