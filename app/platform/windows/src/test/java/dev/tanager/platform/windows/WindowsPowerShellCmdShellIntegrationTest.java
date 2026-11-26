package dev.tanager.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tanager.application.port.out.Integration;
import dev.tanager.application.port.out.LaunchCommand;
import dev.tanager.domain.history.ShellEnvironment;
import dev.tanager.domain.history.WorkspacePath;
import dev.tanager.domain.session.CwdReport;
import dev.tanager.domain.session.IntegrationMode;
import dev.tanager.domain.session.LaunchTemplate;
import dev.tanager.domain.session.OscScanner;
import dev.tanager.domain.session.ProfileId;
import dev.tanager.domain.session.ShellKind;
import dev.tanager.domain.session.ShellProfile;
import dev.tanager.domain.session.WorkingDir;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * 실제 powershell.exe(5.1) · cmd.exe 로 셸 통합 주입을 확인한다(#81, ADR-0014 · 0003). CI 에서는 {@code
 * -PexcludeTags=windows} 가 아닌 Windows 러너에서만 돈다.
 *
 * <p>기동 명령은 {@link WindowsPlatformModule#launchCommand} 가 만든 그대로 쓴다(argv · 작업 디렉터리 · 환경). 바꾸는 것은 셋뿐이다.
 *
 * <ul>
 *   <li>PowerShell 은 앞에 {@code -NoProfile} 을 끼운다. {@code $PROFILE} 은 USERPROFILE · HOME 을 바꿔도 알려진 폴더(문서)에서 정해져서
 *       임시 홈으로 가릴 수 없다. 사용자 프로필(oh-my-posh · StrictMode 등)은 대신 테스트 스크립트로 흉내 낸다 — 아래 {@code userPrompt}
 *       테스트는 {@code -Command} 값 앞에 {@code TEST_USER_PS1} 을 스크립트 블록으로 읽는 문장을 붙인다(프로필 다음 우리 스크립트). 파일로
 *       dot-source 하지 않는 것은 이 PC 의 실행 정책이 결과를 바꾸지 않게 하려고서다.
 *   <li>cmd 는 argv 1번 자리에 {@code /d} 를 끼운다. 이 PC 의 AutoRun(레지스트리 {@code Command Processor\AutoRun}, Clink 등)이
 *       PROMPT 를 바꾸거나 출력을 더해 결과를 흔들지 않게 하려고서다. 실제 기동 argv 는 {@code cmd.exe} 하나다.
 *   <li>띄우는 셸(비교 기준 PowerShell 포함)의 환경에서 {@code PSModulePath} · {@code PSExecutionPolicyPreference} 를 지운다. 테스트
 *       JVM 을 띄운 셸의 흔적이라서다 — GitHub 러너의 pwsh(7) 단계는 7 의 모듈 경로를 물려 5.1 이 {@code Microsoft.PowerShell.Security}
 *       를 못 읽게 하고({@code Get-ExecutionPolicy} 가 없어진다), Bypass 로 띄운 셸은 정책 변수를 물린다. 운영 코드는 사용자가 물려준
 *       값을 건드리지 않는다.
 *   <li>셸은 PTY 대신 파이프로 돌린다. 파이프 출력은 콘솔 코드 페이지(OEM)라 UTF-8 만 받는 {@link OscScanner} 에 넣기 전에 OEM 으로 읽어
 *       UTF-8 로 다시 인코딩한다(ConPTY 는 UTF-8 로 내보낸다). 한글을 OEM 이 표현하지 못하는 PC 에서는 한글 테스트를 건너뛴다.
 * </ul>
 */
@Tag("windows")
@EnabledOnOs(OS.WINDOWS)
class WindowsPowerShellCmdShellIntegrationTest {
    private static final String PS_EXE = "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe";
    private static final String CMD_EXE = "C:\\Windows\\System32\\cmd.exe";
    private static final Duration SHELL_TIMEOUT = Duration.ofSeconds(60);
    private static final String USER_PROMPT = "[IT] $P$G";
    private static final String UNUSUAL_DIR = "한글 폴더 #1";
    private static final String UNUSUAL_APP_DATA = "O'Neil 한글 앱";
    // 우리 스크립트를 실행 정책과 무관하게 다시 읽는 문장(기동 명령의 -Command 와 같은 방식)
    private static final String LOAD_SCRIPT = ". ([scriptblock]::Create([IO.File]::ReadAllText($env:TANAGER_PS1)))";
    private static final String LOAD_USER_SCRIPT =
            ". ([scriptblock]::Create([IO.File]::ReadAllText($env:TEST_USER_PS1))); ";
    // 붙여 쓴 문자열 리터럴이라 셸이 되울린 명령 줄에는 이 표지가 그대로 나오지 않는다
    private static final String POLICY_PROBE = "'PO' + 'LICY=' + ((Get-ExecutionPolicy -List | ForEach-Object "
            + "{ [string]$_.Scope + ':' + [string]$_.ExecutionPolicy }) -join ',') + ';EFFECTIVE=' + (Get-ExecutionPolicy)"
            + " + ';CHILD=' + (powershell.exe -NoProfile -Command Get-ExecutionPolicy)";
    private static final Pattern POLICY_LINE = Pattern.compile("POLICY=([^\\r\\n]*)");
    // 기본 프롬프트 "PS <경로>> " 의 경로. 8.3 짧은 이름(RUNNER~1)과 긴 이름 차이는 isSameFile 로 넘긴다
    private static final Pattern DEFAULT_PROMPT_PATH = Pattern.compile("PS ([A-Za-z]:\\\\[^\\r\\n>]*)>");
    // 테스트 JVM 을 띄운 셸에서 물려받은 값. 띄우는 셸에서 지워 PC · 러너와 무관하게 만든다(클래스 Javadoc)
    private static final List<String> LAUNCHER_VARIABLES = List.of("PSModulePath", "PSExecutionPolicyPreference");

    private static final ShellProfile POWERSHELL = new ShellProfile(
            new ProfileId("powershell"),
            "Windows PowerShell",
            ShellKind.WINDOWS_POWERSHELL,
            new ShellEnvironment.Windows(),
            new LaunchTemplate(PS_EXE, List.of("-NoLogo")),
            IntegrationMode.OSC9_9);
    private static final ShellProfile CMD = new ShellProfile(
            new ProfileId("cmd"),
            "cmd",
            ShellKind.CMD,
            new ShellEnvironment.Windows(),
            new LaunchTemplate(CMD_EXE, List.of()),
            IntegrationMode.OSC9_9);

    @TempDir
    Path temp;

    // ---- PowerShell ----

    @Test
    @DisplayName("PowerShell: 한글 · 공백 폴더에서 띄우면 첫 프롬프트와 cd .. 뒤에 OSC 9;9 가 두 번 나오고 경로가 맞다")
    void powerShellReportsStartAndCd() throws Exception {
        // given
        assumeHangul();
        Path start = Files.createDirectory(temp.resolve(UNUSUAL_DIR));

        // when
        Run run = powerShell(temp.resolve("app"), start, Optional.empty(), "cd ..\nexit\n");

        // then
        assertThat(run.reports()).hasSize(2);
        assertSameDirectory(run.reports().get(0), start);
        assertSameDirectory(run.reports().get(1), temp);
    }

    @Test
    @DisplayName("PowerShell: 한글 · 공백 · ' 가 든 앱 데이터 경로에서도 스크립트를 읽고 오류 출력이 없다")
    void powerShellFindsScriptInUnusualAppData() throws Exception {
        // given
        assumeHangul();
        Path appData = temp.resolve(UNUSUAL_APP_DATA);

        // when
        Run run = powerShell(appData, temp, Optional.empty(), "exit\n");

        // then
        assertThat(run.reports()).as("OSC 9;9").isNotEmpty();
        assertThat(run.text()).doesNotContain("tanager.ps1").doesNotContain("FullyQualifiedErrorId");
    }

    @Test
    @DisplayName("PowerShell: 사용자 prompt(oh-my-posh 흉내)를 먼저 정의한 뒤 dot-source 해도 사용자 prompt 글자와 OSC 가 모두 나온다")
    void powerShellKeepsUserPrompt() throws Exception {
        // given
        String userPrompt = "function global:prompt { 'USERPROMPT> ' }\n";

        // when
        Run run = powerShell(temp.resolve("app"), temp, Optional.of(userPrompt), "exit\n");

        // then
        assertThat(run.text()).contains("USERPROMPT> ");
        assertThat(run.reports()).hasSize(1);
        assertSameDirectory(run.reports().getFirst(), temp);
    }

    @Test
    @DisplayName("PowerShell: 프로필이 Set-StrictMode -Version Latest 를 켜 둬도 사용자 prompt 가 유지되고 OSC 가 나온다")
    void powerShellWorksUnderStrictMode() throws Exception {
        // given
        String userPrompt = "Set-StrictMode -Version Latest\nfunction global:prompt { 'STRICT> ' }\n";

        // when
        Run run = powerShell(temp.resolve("app"), temp, Optional.of(userPrompt), "cd ..\nexit\n");

        // then
        assertThat(run.text()).contains("STRICT> ").doesNotContain("PS>");
        assertThat(run.reports()).hasSize(2);
    }

    @Test
    @DisplayName("PowerShell: 원래 prompt 는 직전 명령의 $LASTEXITCODE 와 $? 를 그대로 본다")
    void powerShellPreservesExitStatus() throws Exception {
        // given
        String userPrompt = "function global:prompt { \"UP[exit=$LASTEXITCODE ok=$?]> \" }\n";

        // when
        Run run = powerShell(
                temp.resolve("app"),
                temp,
                Optional.of(userPrompt),
                "cmd /c exit 3\nGet-Item -LiteralPath 'Z:\\tanager-missing'\nexit\n");

        // then
        assertThat(run.text()).contains("UP[exit=3 ok=False]> ");
        // 두 번째 실패(외부 명령이 아님)는 $LASTEXITCODE 를 바꾸지 않고 $? 만 False 다
        assertThat(count(run.text(), "UP[exit=3 ok=False]> ")).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("PowerShell: 성공한 명령 뒤에는 $? 가 True 로 보인다")
    void powerShellKeepsSuccess() throws Exception {
        // given
        String userPrompt = "function global:prompt { \"UP[ok=$?]> \" }\n";

        // when
        Run run = powerShell(temp.resolve("app"), temp, Optional.of(userPrompt), "Get-Date | Out-Null\nexit\n");

        // then
        assertThat(run.text()).contains("UP[ok=True]> ");
    }

    @Test
    @DisplayName("PowerShell: 스크립트를 두 번 dot-source 해도 OSC 는 프롬프트마다 한 번만 나온다")
    void powerShellDoesNotWrapTwice() throws Exception {
        // given / when
        Run run = powerShell(temp.resolve("app"), temp, Optional.empty(), LOAD_SCRIPT + "\nexit\n");

        // then — 첫 프롬프트 1회 + 다시 source 한 뒤 프롬프트 1회
        assertThat(run.reports()).hasSize(2);
    }

    @Test
    @DisplayName("PowerShell: 레지스트리 드라이브에서는 OSC 를 내지 않는다")
    void powerShellSkipsOscOnRegistryDrive() throws Exception {
        // given — 한글 이름은 OEM 이 한글을 못 쓰는 러너(437)에서 ? 로 바뀐다. 이 테스트의 관심은 공급자라 ASCII · 공백만 쓴다
        Path elsewhere = Files.createDirectory(temp.resolve("other dir"));

        // when
        // 경로는 따옴표로 감싸 명령에 넣지 않고 환경 변수로 넘긴다(' 가 든 임시 폴더에서도 같다)
        Run run = powerShell(
                installIn(temp.resolve("app")),
                temp.resolve("app"),
                temp,
                Optional.empty(),
                Map.of("TEST_DIR", elsewhere.toString()),
                "cd HKCU:\\\ncd -LiteralPath $env:TEST_DIR\nexit\n");

        // then — 시작 경로와 마지막 FileSystem 경로만, HKCU 프롬프트에서는 없다
        assertThat(run.reports()).hasSize(2);
        assertSameDirectory(run.reports().get(1), elsewhere);
        assertThat(run.text()).contains("HKCU:\\>");
    }

    @Test
    @DisplayName("PowerShell: 실행 정책을 바꾸지 않는다 — 범위별 정책 · 실효 정책 · 자식 PowerShell 의 정책이 맨 powershell -NoProfile 과 같다")
    void powerShellKeepsExecutionPolicy() throws Exception {
        // given
        String expected = policyLine(plainPowerShell(POLICY_PROBE));

        // when
        Run run = powerShell(temp.resolve("app"), temp, Optional.empty(), POLICY_PROBE + "\nexit\n");

        // then
        assertThat(run.reports()).as("통합은 동작한다: %s", run.text()).isNotEmpty();
        assertThat(policyLine(run.text())).as("세션 출력: %s", run.text()).isEqualTo(expected);
    }

    @Test
    @DisplayName("PowerShell: 세션 환경에 PSExecutionPolicyPreference 가 생기지 않는다(자식 PowerShell 로 새지 않는다)")
    void powerShellLeavesNoPolicyPreference() throws Exception {
        // given — 하네스가 상속값을 지우므로 남아 있다면 Tanager 가 넣은 것이다

        // when
        Run run = powerShell(
                temp.resolve("app"),
                temp,
                Optional.empty(),
                "'PR' + 'EF=[' + $env:PSExecutionPolicyPreference + ']'\nexit\n");

        // then
        assertThat(run.reports()).as("통합은 동작한다: %s", run.text()).isNotEmpty();
        assertThat(run.text()).contains("PREF=[]");
    }

    @Test
    @DisplayName("PowerShell: TANAGER_PS1 이 없는 파일을 가리키면 오류 출력 없이 기본 프롬프트로 뜨고 OSC 는 없다")
    void powerShellSkipsMissingScript() throws Exception {
        // given
        Integration missing = new Integration.Installed(temp.resolve("없는 폴더"));

        // when
        Run run = powerShell(missing, temp.resolve("app"), temp, Optional.empty(), Map.of(), "exit\n");

        // then
        assertThat(run.reports()).as("OSC 없음: %s", run.text()).isEmpty();
        assertThat(run.text()).doesNotContain("FullyQualifiedErrorId");
        Matcher prompt = DEFAULT_PROMPT_PATH.matcher(run.text());
        assertThat(prompt.find()).as("기본 프롬프트가 나온다: %s", run.text()).isTrue();
        assertSameDirectory(prompt.group(1), temp);
    }

    // ---- cmd ----

    @Test
    @DisplayName("cmd: 한글 · 공백 폴더에서 띄우면 첫 프롬프트에 따옴표 없는 OSC 9;9 가 나오고 경로가 맞다")
    void cmdReportsStartAndCd() throws Exception {
        // given
        assumeHangul();
        Path start = Files.createDirectory(temp.resolve(UNUSUAL_DIR));

        // when
        Run run = cmd(temp.resolve("app"), start, Optional.empty(), "cd ..\nexit\n");

        // then
        assertThat(run.reports()).hasSize(2);
        assertSameDirectory(run.reports().get(0), start);
        assertSameDirectory(run.reports().get(1), temp);
        assertThat(run.raw()).as("OSC 본문에 따옴표가 없다").doesNotContain("]9;9;\"");
    }

    @Test
    @DisplayName("cmd: 한글 · 공백 · ' 가 든 앱 데이터 경로에서도 OSC 가 나온다(PROMPT 에는 경로가 들어가지 않는다)")
    void cmdWorksWithUnusualAppData() throws Exception {
        // given
        assumeHangul();

        // when
        Run run = cmd(temp.resolve(UNUSUAL_APP_DATA), temp, Optional.empty(), "exit\n");

        // then
        assertThat(run.reports()).isNotEmpty();
    }

    @Test
    @DisplayName("cmd: 사용자 PROMPT 가 유지된다 — 프롬프트 글자와 OSC 가 모두 나온다")
    void cmdKeepsUserPrompt() throws Exception {
        // given
        Path start = Files.createDirectory(temp.resolve("work"));

        // when
        Run run = cmd(temp.resolve("app"), start, Optional.of(USER_PROMPT), "exit\n");

        // then
        assertThat(run.text()).contains("[IT] " + start);
        assertSameDirectory(run.reports().getFirst(), start);
    }

    @Test
    @DisplayName("cmd: PROMPT 가 없으면 기본 $P$G 모양(경로>)으로 나온다")
    void cmdDefaultPromptShape() throws Exception {
        // given
        Path start = Files.createDirectory(temp.resolve("work"));

        // when
        Run run = cmd(temp.resolve("app"), start, Optional.empty(), "exit\n");

        // then
        assertThat(run.text()).contains(start + ">");
        assertSameDirectory(run.reports().getFirst(), start);
    }

    // ---- 실행 도우미 ----

    /** {@code raw} 는 UTF-8 로 옮긴 출력 전체, {@code text} 는 ESC 시퀀스를 포함한 같은 문자열, {@code reports} 는 OSC 9;9 경로. */
    private record Run(String raw, List<String> reports) {
        String text() {
            return raw;
        }
    }

    private Run powerShell(Path appData, Path start, Optional<String> userScript, String stdin) throws Exception {
        return powerShell(installIn(appData), appData, start, userScript, Map.of(), stdin);
    }

    private static Integration installIn(Path appData) {
        return install(appData, Optional.empty());
    }

    private Run powerShell(
            Integration integration,
            Path appData,
            Path start,
            Optional<String> userScript,
            Map<String, String> extraEnv,
            String stdin)
            throws Exception {
        WindowsPlatformModule module = moduleWith(appData, Optional.empty());
        LaunchCommand command = module.launchCommand(POWERSHELL, new WorkingDir.At(windowsPath(start)), integration);
        List<String> argv = new ArrayList<>(command.argv());
        argv.add(1, "-NoProfile");
        Map<String, String> extra = new HashMap<>(extraEnv);
        if (userScript.isPresent()) {
            Path script = Files.writeString(
                    Files.createTempFile(temp, "user-", ".ps1"), "\uFEFF" + userScript.get(), StandardCharsets.UTF_8);
            extra.put("TEST_USER_PS1", script.toString());
            int at = argv.indexOf("-Command") + 1;
            argv.set(at, LOAD_USER_SCRIPT + argv.get(at));
        }
        return run(command, argv, extra, stdin);
    }

    /** 비교 기준: 통합도 프로필도 없는 맨 PowerShell 에서 {@code expression} 을 평가한 출력(OEM 으로 읽음). */
    private static String plainPowerShell(String expression) throws Exception {
        ProcessBuilder builder =
                new ProcessBuilder(PS_EXE, "-NoLogo", "-NoProfile", "-Command", expression).redirectErrorStream(true);
        withoutLauncherVariables(builder.environment());
        Process process = builder.start();
        process.getOutputStream().close();
        CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> readAll(process));
        if (!process.waitFor(SHELL_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("기준 PowerShell 이 끝나지 않았다");
        }
        return new String(output.get(10, TimeUnit.SECONDS), oemCharset());
    }

    // ProcessBuilder 의 Windows 환경 맵은 이름을 대소문자 무시로 비교하지만, 맵 구현에 기대지 않고 직접 견준다
    private static void withoutLauncherVariables(Map<String, String> environment) {
        environment.keySet().removeIf(name -> LAUNCHER_VARIABLES.stream().anyMatch(name::equalsIgnoreCase));
    }

    private static String policyLine(String output) {
        Matcher matcher = POLICY_LINE.matcher(output);
        assertThat(matcher.find()).as("정책 줄이 출력에 있다: %s", output).isTrue();
        return matcher.group(1).strip();
    }

    private Run cmd(Path appData, Path start, Optional<String> userPrompt, String stdin) throws Exception {
        Integration integration = install(appData, userPrompt);
        WindowsPlatformModule module = moduleWith(appData, userPrompt);
        LaunchCommand command = module.launchCommand(CMD, new WorkingDir.At(windowsPath(start)), integration);
        List<String> argv = new ArrayList<>(command.argv());
        argv.add(1, "/d");
        return run(command, argv, Map.of(), stdin);
    }

    private Run run(LaunchCommand command, List<String> argv, Map<String, String> extraEnv, String stdin)
            throws Exception {
        Charset oem = oemCharset();
        ProcessBuilder builder = new ProcessBuilder(argv).redirectErrorStream(true);
        command.workingDirectory().ifPresent(dir -> builder.directory(new File(dir)));
        builder.environment().clear();
        builder.environment().putAll(command.environmentOver(System.getenv()));
        withoutLauncherVariables(builder.environment());
        builder.environment().putAll(extraEnv);
        Process process = builder.start();
        CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> readAll(process));
        process.getOutputStream().write(stdin.getBytes(oem));
        process.getOutputStream().close();
        if (!process.waitFor(SHELL_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("셸이 " + SHELL_TIMEOUT + " 안에 끝나지 않았다: " + argv);
        }
        byte[] bytes = output.get(10, TimeUnit.SECONDS);
        // ConPTY 가 내보내는 UTF-8 과 같아지도록 OEM 으로 읽어 UTF-8 로 다시 인코딩한다
        String text = new String(bytes, oem);
        List<String> reports = new ArrayList<>();
        for (CwdReport report :
                OscScanner.initial().feed(text.getBytes(StandardCharsets.UTF_8)).reports()) {
            if (report instanceof CwdReport.WindowsPath path) {
                reports.add(path.path());
            }
        }
        return new Run(text, reports);
    }

    private static WindowsPlatformModule moduleWith(Path appData, Optional<String> prompt) {
        WindowsShellCatalog catalog = new WindowsShellCatalog(
                new FakeCommandRunner(new CommandOutcome.NotStarted("x")),
                key -> Optional.empty(),
                Duration.ofSeconds(1));
        // 테스트 JVM 의 PROMPT 가 새지 않게 PROMPT 는 항상 인자로만 정한다. 나머지는 시스템 환경
        return new WindowsPlatformModule(
                catalog,
                key -> switch (key) {
                    case "APPDATA" -> Optional.of(appData.toString());
                    case "PROMPT" -> prompt;
                    default -> Optional.ofNullable(System.getenv(key));
                },
                System.getProperty("user.home"));
    }

    private static Integration install(Path appDataRoot, Optional<String> prompt) {
        Integration integration = moduleWith(appDataRoot, prompt).integration().install();
        assertThat(integration).as("셸 통합 설치").isInstanceOf(Integration.Installed.class);
        return integration;
    }

    private static WorkspacePath windowsPath(Path path) {
        return WorkspacePath.of(new ShellEnvironment.Windows(), path.toString())
                .value()
                .orElseThrow();
    }

    /** 셸이 알린 경로가 기대한 폴더와 같은 폴더인지(대소문자 · 8.3 짧은 이름 차이는 무시). */
    private static void assertSameDirectory(String reported, Path expected) {
        try {
            assertThat(Files.isSameFile(Path.of(reported), expected))
                    .as("보고된 경로 %s 가 %s 와 같은 폴더", reported, expected)
                    .isTrue();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // 파이프로 연결된 셸은 콘솔 코드 페이지(OEM)로 읽고 쓴다. 같은 방식으로 띄운 chcp 가 그 값을 알려 준다
    private static Charset oemCharset() throws Exception {
        Process process = new ProcessBuilder(CMD_EXE, "/c", "chcp")
                .redirectErrorStream(true)
                .start();
        process.getOutputStream().close();
        String out = new String(readAll(process), StandardCharsets.ISO_8859_1);
        process.waitFor(10, TimeUnit.SECONDS);
        Matcher matcher = Pattern.compile("(\\d{3,5})\\s*$").matcher(out.strip());
        if (matcher.find()) {
            for (String prefix : List.of("x-windows-", "x-IBM", "IBM", "windows-", "cp")) {
                String name = prefix + matcher.group(1);
                if (Charset.isSupported(name)) {
                    return Charset.forName(name);
                }
            }
        }
        return Charset.forName(System.getProperty("native.encoding", "UTF-8"));
    }

    private static void assumeHangul() throws Exception {
        Assumptions.assumeTrue(oemCharset().newEncoder().canEncode("한글"), "콘솔 코드 페이지가 한글을 표현하지 못한다");
    }

    private static byte[] readAll(Process process) {
        try {
            return process.getInputStream().readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }
}
