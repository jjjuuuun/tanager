package dev.tanager.platform.windows;

import dev.tanager.application.port.out.Integration;
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
    private static final String BASH_SCRIPT = "tanager.bash";
    private static final String RC_VARIABLE = "TANAGER_RC";
    // wsl.exe 는 -- 뒤 명령을 리눅스 기본 셸로 실행하므로 변수가 거기서 펼쳐진다. 큰따옴표가 없으면 앱 데이터 경로의
    // 공백(사용자 이름 등)에서 단어가 갈린다(ADR-0014). 공백이 없는 원소라 Windows 명령줄로 옮길 때 다시 감싸지 않는다
    private static final String RC_ARGUMENT = "\"$" + RC_VARIABLE + "\"";
    // rcfile 을 못 읽으면(지워짐 · 권한) 맨 bash 가 아니라 통합 없는 M2 의 bash -i 로 물러선다(~/.bashrc 는 읽는다).
    // 원소마다 공백이 없어야 Windows 명령줄로 옮길 때 감싸이지 않고 기본 셸이 연산자로 읽는다
    private static final List<String> RC_OR_PLAIN_BASH = List.of(
            "[",
            "-r",
            RC_ARGUMENT,
            "]",
            "&&",
            "exec",
            "bash",
            "--rcfile",
            RC_ARGUMENT,
            "-i",
            "||",
            "exec",
            "bash",
            "-i");
    // /p: WSL 이 Windows 경로를 자기 마운트 경로로 바꿔 넘긴다 — automount 루트를 몰라도 된다(ADR-0014)
    private static final String RC_WSLENV_ENTRY = RC_VARIABLE + "/p";
    private static final String WSLENV = "WSLENV";
    private static final String WSLENV_SEPARATOR = ":";

    private WindowsLaunchCommands() {}

    /**
     * {@code dir} 이 {@link WorkingDir.Home} 이면 WSL 은 {@code ~}, Windows 셸은 {@code userProfile} 에서 띄운다. 셸 통합이
     * 설치돼 있으면 WSL bash 에 rcfile 을 주입하고, 앱 환경의 {@code wslenv} 는 보존한 채 {@code TANAGER_RC/p} 를 붙인다.
     */
    static LaunchCommand of(
            ShellProfile profile,
            WorkingDir dir,
            String userProfile,
            Optional<String> wslenv,
            Integration integration) {
        if (dir instanceof WorkingDir.At at && !profile.env().equals(at.path().env())) {
            throw new IllegalArgumentException(
                    "프로파일 환경 " + profile.env() + " 과 경로 환경 " + at.path().env() + " 이 다르다");
        }
        LaunchTemplate template = profile.launch();
        return switch (profile.env()) {
            // 리눅스 경로는 Windows 작업 디렉터리로 줄 수 없다 — wsl.exe 의 --cd 로 넘긴다(ADR-0007)
            case ShellEnvironment.Wsl _ -> wsl(template, pathOr(dir, WSL_HOME), wslenv, integration);
            case ShellEnvironment.Windows _ ->
                new LaunchCommand(
                        templateArgv(template), Optional.of(pathOr(dir, userProfile)), Map.of(), WINDOWS_NAMES);
        };
    }

    private static LaunchCommand wsl(
            LaunchTemplate template, String cd, Optional<String> wslenv, Integration integration) {
        List<String> argv = templateArgv(template);
        argv.addAll(List.of("--cd", cd, "--"));
        return switch (integration) {
            case Integration.Installed(var directory) -> {
                argv.addAll(RC_OR_PLAIN_BASH);
                Map<String, String> environment =
                        Map.of(RC_VARIABLE, directory.resolve(BASH_SCRIPT).toString(), WSLENV, mergedWslenv(wslenv));
                yield new LaunchCommand(argv, Optional.empty(), environment, WINDOWS_NAMES);
            }
            case Integration.Unavailable _ -> {
                argv.addAll(List.of("bash", "-i"));
                yield new LaunchCommand(argv, Optional.empty(), Map.of(), WINDOWS_NAMES);
            }
        };
    }

    /**
     * 사용자의 WSLENV 항목은 순서대로 두고 우리 항목을 맨 뒤에 하나만 둔다. Tanager 안에서 Tanager 를 띄우면 상속한 WSLENV 에 이미
     * {@code TANAGER_RC} 가 있다 — 플래그가 달라도 이름이 같으면 지운다(Windows 처럼 대소문자 무시).
     */
    private static String mergedWslenv(Optional<String> wslenv) {
        List<String> entries = new ArrayList<>();
        for (String raw : wslenv.orElse("").split(WSLENV_SEPARATOR, -1)) {
            String entry = raw.strip();
            if (!entry.isEmpty() && !isRcEntry(entry)) {
                entries.add(entry);
            }
        }
        entries.add(RC_WSLENV_ENTRY);
        return String.join(WSLENV_SEPARATOR, entries);
    }

    private static boolean isRcEntry(String entry) {
        int flags = entry.indexOf('/');
        String name = flags < 0 ? entry : entry.substring(0, flags);
        return name.equalsIgnoreCase(RC_VARIABLE);
    }

    private static String pathOr(WorkingDir dir, String home) {
        return switch (dir) {
            case WorkingDir.Home _ -> home;
            case WorkingDir.At at -> at.path().path();
        };
    }

    private static List<String> templateArgv(LaunchTemplate template) {
        List<String> argv = new ArrayList<>();
        argv.add(template.executable());
        argv.addAll(template.args());
        return argv;
    }
}
