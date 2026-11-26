package dev.tanager.platform.windows;

import dev.tanager.application.port.out.Integration;
import dev.tanager.application.port.out.LaunchCommand;
import dev.tanager.application.port.out.LaunchCommand.EnvNames;
import dev.tanager.domain.history.ShellEnvironment;
import dev.tanager.domain.session.LaunchTemplate;
import dev.tanager.domain.session.ShellKind;
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
    // wsl.exe 옵션의 끝. 뒤는 리눅스 기본 셸의 -c 가 읽는 원시 명령줄이다
    private static final String WSL_SEPARATOR = "--";
    private static final String PS_SCRIPT = "tanager.ps1";
    private static final String PS_VARIABLE = "TANAGER_PS1";
    // -Command 문자열은 고정이다. 스크립트 경로(사용자 이름이 든 앱 데이터 경로)는 환경 변수로만 넘겨 PowerShell 의 따옴표 ·
    // 이스케이프 규칙(' 와 U+2018–U+201B)을 타지 않게 한다(#107, ADR-0014). 변수 값은 다시 단어로 갈리지 않는다.
    // 파일이 아니라 문자열로 만든 스크립트 블록을 dot-source 한다 — 실행 정책은 스크립트 파일에만 적용되므로 -ExecutionPolicy
    // Bypass 없이도 읽히고, 사용자의 정책(프로필 · 세션 · 자식 PowerShell)은 그대로 남는다. 파일이 없으면 조용히 건너뛴다
    private static final String PS_LOAD_SCRIPT = "if (Test-Path -LiteralPath $env:" + PS_VARIABLE + ") "
            + "{ . ([scriptblock]::Create([IO.File]::ReadAllText($env:" + PS_VARIABLE + "))) }";
    private static final List<String> PS_INTEGRATION_ARGS = List.of("-NoExit", "-Command", PS_LOAD_SCRIPT);
    private static final String PROMPT = "PROMPT";
    // cmd 의 $e 는 ESC, $P 는 현재 경로. 따옴표는 붙이지 않는다 — OscScanner 가 따옴표 없는 OSC 9;9 도 읽는다
    private static final String PROMPT_PREFIX = "$e]9;9;$P$e\\";
    // PROMPT 가 없을 때 cmd 가 쓰는 기본값
    private static final String DEFAULT_PROMPT = "$P$G";

    private WindowsLaunchCommands() {}

    /** 상속한 {@code PROMPT} 가 없는 것으로 보고 {@link #of(ShellProfile, WorkingDir, String, Optional, Optional, Integration)}. */
    static LaunchCommand of(
            ShellProfile profile,
            WorkingDir dir,
            String userProfile,
            Optional<String> wslenv,
            Integration integration) {
        return of(profile, dir, userProfile, wslenv, Optional.empty(), integration);
    }

    /**
     * {@code dir} 이 {@link WorkingDir.Home} 이면 WSL 은 {@code ~}, Windows 셸은 {@code userProfile} 에서 띄운다. 셸 통합이
     * 설치돼 있으면 셸마다 이렇게 주입한다(ADR-0014).
     *
     * <ul>
     *   <li>WSL bash: rcfile 을 주입하고, 앱 환경의 {@code wslenv} 는 보존한 채 {@code TANAGER_RC/p} 를 붙인다.
     *   <li>PowerShell 5.1: 환경 변수 {@code TANAGER_PS1} 의 스크립트를 {@code -Command} 에서 스크립트 블록으로 dot-source 한다(실행
     *       정책을 바꾸지 않는다).
     *   <li>cmd: 상속한 {@code prompt}(없거나 공백이면 {@code $P$G}) 앞에 OSC 9;9 를 붙인 {@code PROMPT} 를 준다.
     * </ul>
     */
    static LaunchCommand of(
            ShellProfile profile,
            WorkingDir dir,
            String userProfile,
            Optional<String> wslenv,
            Optional<String> prompt,
            Integration integration) {
        if (dir instanceof WorkingDir.At at && !profile.env().equals(at.path().env())) {
            throw new IllegalArgumentException(
                    "프로파일 환경 " + profile.env() + " 과 경로 환경 " + at.path().env() + " 이 다르다");
        }
        LaunchTemplate template = profile.launch();
        LaunchCommand command = switch (profile.env()) {
            // 리눅스 경로는 Windows 작업 디렉터리로 줄 수 없다 — wsl.exe 의 --cd 로 넘긴다(ADR-0007)
            case ShellEnvironment.Wsl _ -> wsl(template, pathOr(dir, WSL_HOME), wslenv, integration);
            // 작업 디렉터리는 argv 가 아니라 lpCurrentDirectory 로 넘긴다 — 셸이 해석하는 문자열에 경로를 넣지 않는다(#107)
            case ShellEnvironment.Windows _ ->
                windows(profile.kind(), template, pathOr(dir, userProfile), prompt, integration);
        };
        requireSafeArgv(profile.env(), command.argv());
        return command;
    }

    /**
     * 방어 겹(#107). pty4j 0.14 의 {@code joinCmdArgs} 는 공백 · 탭이 있는 원소를 큰따옴표로 감싸기만 한다 — 안의 {@code "} 는
     * 이스케이프하지 않고, 끝 {@code \} 는 하나만 겹치고, {@code "} 로 시작하는 원소는 감싸지도 않는다. 그런 원소는 명령줄에서 인자가
     * 갈라진다. 경로는 {@code WorkspacePath} 가 이미 막지만 템플릿 · 앞으로 생길 인자까지 여기서 한 번 더 막는다.
     *
     * <p>WSL 은 {@code --} 앞 원소에 {@code "} 가 있기만 해도 거부한다. wsl.exe 2.5.10 은 {@code "} 로 시작하는 토큰을 다음 {@code "}
     * 까지 한 인자로 읽고 {@code \"} · {@code ""} 이스케이프가 없어서, 감싸지 않는 원소의 {@code "} 도 뒤 인자를 삼켜 {@code --cd} 값
     * 뒤를 명령으로 실행하게 만들 수 있다. 템플릿이 {@code --} 를 잘못 넣어도 경로 원소가 검사에서 빠지지 않게 마지막 {@code --} 까지
     * 본다. {@code --} 뒤는 리눅스 셸이 읽는 원시 명령줄이라 {@code "$TANAGER_RC"} 는 그대로 둔다.
     *
     * <p>이 검사는 wsl.exe 인자 분리만 막는다. cmd {@code /c} · PowerShell {@code -Command} 문자열의 셸 해석은 막지 못한다(ADR-0014).
     * 그래서 PowerShell 의 {@code -Command} 는 고정 문자열이고 경로는 환경 변수로만 넘긴다(공백이 있어 감싸이지만 {@code "} 가 없다).
     *
     * @throws IllegalArgumentException 안전하지 않은 원소가 있을 때. 정상 흐름에서는 일어나지 않는 버그다
     */
    static void requireSafeArgv(ShellEnvironment env, List<String> argv) {
        for (String arg : argv) {
            boolean wrapped = arg.indexOf(' ') >= 0 || arg.indexOf('\t') >= 0;
            if (wrapped && (arg.indexOf('"') >= 0 || arg.endsWith("\\"))) {
                throw new IllegalArgumentException("따옴표로 감쌀 기동 인자에 \" 가 있거나 \\ 로 끝난다: " + arg);
            }
        }
        switch (env) {
            case ShellEnvironment.Wsl _ -> {
                int separator = argv.lastIndexOf(WSL_SEPARATOR);
                List<String> options = separator < 0 ? argv : argv.subList(0, separator);
                for (String arg : options) {
                    if (arg.indexOf('"') >= 0) {
                        throw new IllegalArgumentException("wsl.exe 의 -- 앞 인자에 \" 가 있다: " + arg);
                    }
                }
            }
            case ShellEnvironment.Windows _ -> {}
        }
    }

    private static LaunchCommand wsl(
            LaunchTemplate template, String cd, Optional<String> wslenv, Integration integration) {
        List<String> argv = templateArgv(template);
        argv.addAll(List.of("--cd", cd, WSL_SEPARATOR));
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

    private static LaunchCommand windows(
            ShellKind kind, LaunchTemplate template, String cwd, Optional<String> prompt, Integration integration) {
        List<String> argv = templateArgv(template);
        Map<String, String> environment = switch (integration) {
            case Integration.Installed(var directory) ->
                switch (kind) {
                    case WINDOWS_POWERSHELL -> {
                        argv.addAll(PS_INTEGRATION_ARGS);
                        yield Map.of(PS_VARIABLE, directory.resolve(PS_SCRIPT).toString());
                    }
                    case CMD -> Map.of(PROMPT, mergedPrompt(prompt));
                    case BASH, ZSH -> Map.of();
                };
            case Integration.Unavailable _ -> Map.of();
        };
        return new LaunchCommand(argv, Optional.of(cwd), environment, WINDOWS_NAMES);
    }

    /**
     * 사용자 {@code PROMPT} 는 지우지 않고 앞에 OSC 9;9 만 붙인다. 없거나 공백뿐이면 cmd 기본값 {@code $P$G} 를 쓴다. Tanager 안에서
     * Tanager 를 띄우면 상속값이 이미 접두로 시작한다 — cmd 의 {@code $} 코드처럼 대소문자를 무시하고 보고 다시 붙이지 않는다.
     */
    private static String mergedPrompt(Optional<String> prompt) {
        String base = prompt.filter(value -> !value.isBlank()).orElse(DEFAULT_PROMPT);
        if (base.regionMatches(true, 0, PROMPT_PREFIX, 0, PROMPT_PREFIX.length())) {
            return base;
        }
        return PROMPT_PREFIX + base;
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
