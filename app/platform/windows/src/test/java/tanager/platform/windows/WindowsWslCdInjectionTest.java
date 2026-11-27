package tanager.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tanager.application.port.out.Integration;
import tanager.application.port.out.LaunchCommand;
import tanager.domain.history.PathError;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.session.CwdReport;
import tanager.domain.session.IntegrationMode;
import tanager.domain.session.LaunchTemplate;
import tanager.domain.session.OscScanner;
import tanager.domain.session.ProfileId;
import tanager.domain.session.ShellKind;
import tanager.domain.session.ShellProfile;
import tanager.domain.session.WorkingDir;

/**
 * #107 인수 조건 4 — 실제 wsl.exe 로 시작 경로 명령 주입을 확인한다. 배포판이 없으면 건너뛴다. CI 에서는 {@code -PexcludeTags=wsl}.
 *
 * <p>pty4j 는 adapter.pty 에 가둬 두었으므로(ArchUnit) 여기서는 쓰지 않는다. 대신 pty4j 0.14 {@code joinCmdArgs} 를 옮긴 {@link
 * WslCommandLineModel#joinCmdArgs} 로 명령줄을 만들고, Windows PowerShell 5.1 의 {@code ProcessStartInfo.Arguments} 에 그대로 넣어
 * {@code CreateProcess} 에 넘긴다 — Java {@code ProcessBuilder} 는 원소를 자기 규칙으로 다시 감싸므로 pty4j 와 같은 명령줄이 되지 않는다.
 * 명령줄은 환경 변수로 넘겨 PowerShell 의 따옴표 해석을 거치지 않게 한다. 다른 점은 .NET 이 실행 파일 이름(argv[0])을 {@code "…"}
 * 로 감싼다는 것뿐이다.
 */
@Tag("wsl")
@EnabledOnOs(OS.WINDOWS)
class WindowsWslCdInjectionTest {
    private static final String WSL_EXE = "C:\\Windows\\System32\\wsl.exe";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    // .NET Framework 4.x · PowerShell 5.1 문법만. 표준 입출력은 바이트 그대로 base64 로 옮겨 코드 페이지를 거치지 않는다
    private static final String LAUNCHER_SCRIPT = String.join(
            "\n",
            "$ErrorActionPreference = 'Stop'",
            "$psi = New-Object System.Diagnostics.ProcessStartInfo",
            "$psi.FileName = $env:TANAGER_IT_EXE",
            "$psi.Arguments = $env:TANAGER_IT_ARGS",
            "$psi.UseShellExecute = $false",
            "$psi.RedirectStandardInput = $true",
            "$psi.RedirectStandardOutput = $true",
            "$psi.RedirectStandardError = $true",
            "$p = [System.Diagnostics.Process]::Start($psi)",
            "$out = New-Object System.IO.MemoryStream",
            "$err = New-Object System.IO.MemoryStream",
            "$outCopy = $p.StandardOutput.BaseStream.CopyToAsync($out)",
            "$errCopy = $p.StandardError.BaseStream.CopyToAsync($err)",
            "$stdin = [Convert]::FromBase64String($env:TANAGER_IT_STDIN)",
            "$p.StandardInput.BaseStream.Write($stdin, 0, $stdin.Length)",
            "$p.StandardInput.BaseStream.Flush()",
            "$p.StandardInput.Close()",
            "if (-not $p.WaitForExit(" + TIMEOUT.toMillis()
                    + ")) { $p.Kill(); [Console]::Out.WriteLine('TIMEOUT'); exit 3 }",
            "$outCopy.Wait()",
            "$errCopy.Wait()",
            "[Console]::Out.WriteLine('CODE:' + $p.ExitCode)",
            "[Console]::Out.WriteLine('OUT:' + [Convert]::ToBase64String($out.ToArray()))",
            "[Console]::Out.WriteLine('ERR:' + [Convert]::ToBase64String($err.ToArray()))");

    @TempDir
    Path appData;

    private String distro;
    private String base;

    @BeforeEach
    void prepare() throws Exception {
        distro = firstDistro();
        base = "/tmp/tanager-cd-it-" + UUID.randomUUID();
        mkdir(base + "/home");
    }

    @AfterEach
    void cleanUp() throws Exception {
        if (distro == null) {
            return; // 배포판이 없어 건너뛴 테스트
        }
        runWsl(List.of("rm", "-rf", base));
    }

    @Test
    @DisplayName("주입 경로 q\" -- touch …/proof # 는 WorkspacePath 가 거부하고, 우회해 만든 --cd argv 는 방어 검사가 거부한다")
    void injectionPathIsRejectedAtBothLayers() throws Exception {
        // given
        String injection = base + "/q\" -- touch " + base + "/proof #";
        ShellEnvironment.Wsl env = new ShellEnvironment.Wsl(distro);
        List<String> bypassed = List.of(WSL_EXE, "-d", distro, "--cd", injection, "--", "bash", "-i");

        // when / then
        assertThat(WorkspacePath.of(env, injection).error()).containsInstanceOf(PathError.UnsafeCharacter.class);
        assertThatThrownBy(() -> WindowsLaunchCommands.requireSafeArgv(env, bypassed))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(exists(base + "/proof")).isFalse();
    }

    @Test
    @DisplayName("하네스 민감도: 두 검사를 모두 우회한 명령줄을 띄우면 실제로 proof 가 생긴다(깨지면 ADR-0014 #107 의 실측을 다시 본다)")
    void bypassingBothLayersReallyInjects() throws Exception {
        // given cd 대상이 있어야 wsl.exe 가 명령까지 간다
        mkdir(base + "/q");
        String injection = base + "/q\" -- touch " + base + "/proof #";
        List<String> bypassed = List.of(WSL_EXE, "-d", distro, "--cd", injection, "--", "bash", "-i");

        // when
        Launched launched = launchLikePty4j(bypassed, System.getenv(), "exit\n");

        // then
        assertThat(exists(base + "/proof")).as("출력: %s", launched).isTrue();
    }

    @Test
    @DisplayName("대조군: 허용되는 위험해 보이는 경로(' $ ` 한글 C1 U+2028 -- touch #)는 그 폴더에서 셸이 뜨고 proof 는 생기지 않는다")
    void acceptedLookalikePathStartsThereWithoutInjection() throws Exception {
        // given
        String start = base + "/it's $HOME `id` 한글\u3000\u0085\u2028q -- touch " + base + "/proof #";
        mkdir(start);
        ShellEnvironment.Wsl env = new ShellEnvironment.Wsl(distro);
        WorkspacePath path = WorkspacePath.of(env, start).value().orElseThrow();
        Integration integration = installedIntegration();
        LaunchCommand command = module().launchCommand(profile(env), new WorkingDir.At(path), integration);

        // when
        Launched launched = launchLikePty4j(
                withHome(command.argv()),
                command.environmentOver(System.getenv()),
                "printf 'PWD<%s>\\n' \"$PWD\"\nexit\n");

        // then
        assertThat(exists(base + "/proof")).as("출력: %s", launched).isFalse();
        assertThat(launched.code()).as("출력: %s", launched).isZero();
        assertThat(marked(launched.out(), "PWD")).isEqualTo(start);
        List<String> cwds = new ArrayList<>();
        for (CwdReport report : OscScanner.initial().feed(launched.outBytes()).reports()) {
            if (report instanceof CwdReport.FileUri uri) {
                cwds.add(uri.path());
            }
        }
        assertThat(cwds).as("셸 통합의 OSC 7 도 같은 경로를 알린다").first().isEqualTo(start);
    }

    // ---- 실행 도우미 ----

    private record Launched(int code, byte[] outBytes, String err) {
        String out() {
            return new String(outBytes, StandardCharsets.UTF_8);
        }

        @Override
        public String toString() {
            return "code=" + code + " out=" + out() + " err=" + err;
        }
    }

    /** {@code argv} 를 pty4j 처럼 이은 명령줄 그대로 wsl.exe 를 띄운다. {@code environment} 는 PowerShell 과 wsl.exe 의 환경 전체다. */
    private Launched launchLikePty4j(List<String> argv, Map<String, String> environment, String stdin)
            throws Exception {
        String line = WslCommandLineModel.joinCmdArgs(argv);
        String exe = argv.getFirst();
        assertThat(line).as("실행 파일 이름은 감싸지지 않는다").startsWith(exe + " ");
        Map<String, String> env = new HashMap<>(environment);
        env.put("TANAGER_IT_EXE", exe);
        env.put("TANAGER_IT_ARGS", line.substring(exe.length() + 1));
        env.put("TANAGER_IT_STDIN", Base64.getEncoder().encodeToString(stdin.getBytes(StandardCharsets.UTF_8)));
        env.put("WSL_UTF8", "1");
        String encoded = Base64.getEncoder().encodeToString(LAUNCHER_SCRIPT.getBytes(StandardCharsets.UTF_16LE));
        ProcessBuilder builder = new ProcessBuilder(
                        "powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
                .redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().putAll(env);
        Outcome outcome = run(builder, TIMEOUT.plusSeconds(30));
        // PowerShell 이 내보내는 것은 ASCII(base64 · 숫자)뿐이다. 오류 문구는 OEM 코드 페이지일 수 있어 진단용으로만 본다
        String text = outcome.text();
        Matcher code = Pattern.compile("CODE:(-?\\d+)").matcher(text);
        Matcher out = Pattern.compile("OUT:([A-Za-z0-9+/=]*)").matcher(text);
        Matcher err = Pattern.compile("ERR:([A-Za-z0-9+/=]*)").matcher(text);
        assertThat(code.find() && out.find() && err.find())
                .as("PowerShell 실행기 출력: %s", text)
                .isTrue();
        return new Launched(
                Integer.parseInt(code.group(1)),
                Base64.getDecoder().decode(out.group(1)),
                new String(Base64.getDecoder().decode(err.group(1)), StandardCharsets.UTF_8));
    }

    // 사용자 홈의 .bashrc 가 cd 하거나 출력을 더럽히지 않게 bash 마다 임시 HOME 을 준다. -- 앞 원소는 손대지 않는다
    private List<String> withHome(List<String> argv) {
        List<String> out = new ArrayList<>();
        boolean afterSeparator = false;
        for (String arg : argv) {
            if (afterSeparator && arg.equals("bash")) {
                out.addAll(List.of("env", "HOME=" + base + "/home"));
            }
            afterSeparator |= arg.equals("--");
            out.add(arg);
        }
        return out;
    }

    private ShellProfile profile(ShellEnvironment.Wsl env) {
        return new ShellProfile(
                new ProfileId("wsl:" + distro),
                distro,
                ShellKind.BASH,
                env,
                new LaunchTemplate(WSL_EXE, List.of("-d", distro)),
                IntegrationMode.OSC7);
    }

    private WindowsPlatformModule module() {
        WindowsShellCatalog catalog = new WindowsShellCatalog(
                new FakeCommandRunner(new CommandOutcome.NotStarted("x")),
                key -> Optional.empty(),
                Duration.ofSeconds(1));
        return new WindowsPlatformModule(
                catalog,
                key -> key.equals("APPDATA")
                        ? Optional.of(appData.toString())
                        : Optional.ofNullable(System.getenv(key)),
                System.getProperty("user.home"));
    }

    private Integration installedIntegration() {
        Integration integration = module().integration().install();
        assertThat(integration).as("셸 통합 설치").isInstanceOf(Integration.Installed.class);
        return integration;
    }

    private static String marked(String text, String label) {
        Matcher matcher =
                Pattern.compile(Pattern.quote(label) + "<([^>\\r\\n]*)>").matcher(text);
        String last = null;
        while (matcher.find()) {
            last = matcher.group(1);
        }
        assertThat(last).as("출력에 %s<...> 가 있어야 한다: %s", label, text).isNotNull();
        return last;
    }

    private void mkdir(String path) throws Exception {
        assertThat(runWsl(List.of("mkdir", "-p", path)).code())
                .as("mkdir %s", path)
                .isZero();
    }

    private boolean exists(String path) throws Exception {
        return runWsl(List.of("test", "-e", path)).code() == 0;
    }

    private record Outcome(int code, String text) {}

    // 인자에 " 가 없으므로 ProcessBuilder 가 감싸도 원소가 그대로 간다
    private Outcome runWsl(List<String> command) throws Exception {
        List<String> argv = new ArrayList<>(List.of(WSL_EXE, "-d", distro, "--exec"));
        argv.addAll(command);
        ProcessBuilder builder = new ProcessBuilder(argv).redirectErrorStream(true);
        builder.environment().put("WSL_UTF8", "1");
        return run(builder, Duration.ofSeconds(30));
    }

    private static Outcome run(ProcessBuilder builder, Duration timeout) throws Exception {
        Process process = builder.start();
        process.getOutputStream().close();
        CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> readAll(process));
        if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("명령이 " + timeout + " 안에 끝나지 않았다: " + builder.command());
        }
        return new Outcome(process.exitValue(), new String(output.get(10, TimeUnit.SECONDS), StandardCharsets.UTF_8));
    }

    private static byte[] readAll(Process process) {
        try {
            return process.getInputStream().readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String firstDistro() throws Exception {
        Optional<String> fromEnv =
                Optional.ofNullable(System.getenv("TANAGER_TEST_WSL_DISTRO")).filter(name -> !name.isBlank());
        if (fromEnv.isPresent()) {
            return fromEnv.get();
        }
        // WSL_UTF8 을 주지 않은 wsl.exe 의 목록은 UTF-16LE 다 — 명시적으로 그렇게 읽는다
        ProcessBuilder builder = new ProcessBuilder(WSL_EXE, "--list", "--quiet");
        builder.environment().remove("WSL_UTF8");
        Process process = builder.start();
        process.getOutputStream().close();
        CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> readAll(process));
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("wsl.exe --list 가 끝나지 않았다");
        }
        Optional<String> found = new String(output.get(10, TimeUnit.SECONDS), StandardCharsets.UTF_16LE)
                .lines()
                .map(line -> line.replace("\uFEFF", "").trim())
                .filter(line -> !line.isEmpty() && !line.startsWith("docker-desktop"))
                .findFirst();
        Assumptions.assumeTrue(found.isPresent(), "설치된 WSL 배포판이 없다");
        return found.orElseThrow();
    }
}
