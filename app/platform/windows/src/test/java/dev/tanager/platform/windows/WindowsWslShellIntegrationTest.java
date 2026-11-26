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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
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

/**
 * 실제 wsl.exe 로 셸 통합(rcfile) 주입을 확인한다(#55, ADR-0014 · 0003). 배포판이 없으면 건너뛴다. CI 에서는 {@code -PexcludeTags=wsl}.
 *
 * <p>사용자 홈을 건드리지 않으려고 기동 명령의 {@code bash} 앞에만 {@code env HOME=<임시 홈>} 을 끼운다 — 나머지 인자(--cd ·
 * --rcfile "$TANAGER_RC" · 환경 · WSLENV)는 {@link WindowsPlatformModule#launchCommand} 가 만든 그대로다. 셸은 PTY 대신 파이프로
 * 돌린다(프롬프트는 stderr 로 가지만 PROMPT_COMMAND 와 OSC 7 은 똑같이 나간다).
 */
@Tag("wsl")
@EnabledOnOs(OS.WINDOWS)
class WindowsWslShellIntegrationTest {
    private static final String WSL_EXE = "C:\\Windows\\System32\\wsl.exe";
    private static final Duration SHELL_TIMEOUT = Duration.ofSeconds(60);
    private static final String SUDO_HINT = "To run a command as administrator";

    @TempDir
    Path appData;

    private String distro;
    private String home;
    private final List<String> createdOnWsl = new ArrayList<>();

    @BeforeEach
    void prepareFakeHome() throws Exception {
        distro = firstDistro();
        home = "/tmp/tanager-wsl-it-" + UUID.randomUUID();
        mkdir(home);
    }

    @AfterEach
    void removeFakeHome() throws Exception {
        for (String path : createdOnWsl) {
            wslExec(List.of("rm", "-rf", path));
        }
    }

    // ---- 인수 조건 ----

    @Test
    @DisplayName("첫 프롬프트에 OSC 7 로 시작 경로가 나온다")
    void firstPromptReportsStartDirectory() throws Exception {
        // given / when
        Run run = interactive("/tmp", "exit\n");

        // then
        assertThat(run.cwds()).first().isEqualTo("/tmp");
    }

    @Test
    @DisplayName("한글 · 공백 · ' 가 든 시작 경로와 cd 한 경로를 OSC 7 이 그대로 알린다")
    void osc7ReportsUnusualDirectories() throws Exception {
        // given
        String start = "/tmp/it's 한글 폴더 #1-" + UUID.randomUUID();
        String target = "/tmp/한글 폴더 #2-" + UUID.randomUUID();
        mkdir(start);
        mkdir(target);

        // when
        Run run = interactive(start, "cd '" + target + "'\nexit\n");

        // then
        assertThat(run.cwds()).containsSubsequence(start, target);
    }

    @Test
    @DisplayName("한글 · 공백 · ' 가 든 Windows 앱 데이터 경로에서도 TANAGER_RC 를 찾아 셸 통합이 동작한다")
    void unusualWindowsAppDataPathStillFindsRcfile() throws Exception {
        // given
        Path unusual = Files.createDirectory(appData.resolve("사용자 'x 공백"));

        // when
        Run run = interactive(unusual, "/tmp", "exit\n");

        // then
        assertThat(run.cwds()).first().isEqualTo("/tmp");
        assertThat(run.text()).doesNotContain("tanager.bash");
    }

    @Test
    @DisplayName("rcfile 이 지워졌거나 읽을 수 없으면 통합 없는 bash -i 로 물러서 ~/.bashrc 는 그대로 읽힌다")
    void missingRcfileFallsBackToPlainInteractiveBash() throws Exception {
        // given
        writeHomeFile(".bashrc", "alias tanager_it_alias='echo \"ALIAS<defined>\"'\n");
        String missing = appData.resolve("없는 폴더").resolve("tanager.bash").toString();

        // when
        Run run = interactive(appData, "/tmp", "tanager_it_alias\nexit\n", Map.of("TANAGER_RC", missing));

        // then
        assertThat(marked(run.text(), "ALIAS")).isEqualTo("defined");
        assertThat(run.cwds()).as("통합이 없으니 OSC 7 도 없다").isEmpty();
    }

    @Test
    @DisplayName("~/.profile 이 늘린 PATH 가 그냥 로그인 셸을 띄울 때와 같다")
    void pathFromProfileMatchesLoginShell() throws Exception {
        // given
        writeHomeFile(".profile", "PATH=\"$HOME/bin:$PATH\"\n");

        // when
        Run run = interactive("/tmp", "echo \"PATH<$PATH>\"\nexit\n");
        // 따옴표가 든 인자는 Windows ProcessBuilder 가 이스케이프하지 않는다 — 스크립트 파일로 로그인 셸을 돌린다
        writeHomeFile("print-path.sh", "echo \"PATH<$PATH>\"\n");
        String login = wslExec(List.of("env", "HOME=" + home, "bash", "-l", home + "/print-path.sh"))
                .strip();

        // then
        assertThat(marked(run.text(), "PATH")).startsWith(home + "/bin:").isEqualTo(marked(login, "PATH"));
    }

    @Test
    @DisplayName("~/.bash_profile 이 없고 ~/.profile 이 ~/.bashrc 를 읽으면 .bashrc 는 한 번만 실행된다")
    void bashrcRunsOnceThroughProfile() throws Exception {
        // given
        writeHomeFile(".profile", "[ -f \"$HOME/.bashrc\" ] && . \"$HOME/.bashrc\"\n");
        writeHomeFile(".bashrc", "TANAGER_BASHRC_MARKS=\"${TANAGER_BASHRC_MARKS-}x\"\n");

        // when
        Run run = interactive("/tmp", "echo \"MARKS<$TANAGER_BASHRC_MARKS>\"\nexit\n");

        // then
        assertThat(marked(run.text(), "MARKS")).isEqualTo("x");
    }

    @Test
    @DisplayName("Debian 계열 /etc/bash.bashrc 의 sudo 안내는 셸을 띄울 때 한 번만 나온다")
    void systemBashrcRunsOnce() throws Exception {
        // given
        boolean hintApplies = wslExecCode(List.of(
                        "bash",
                        "-c",
                        "grep -q '" + SUDO_HINT + "' /etc/bash.bashrc && id -nG | grep -qwE 'sudo|admin'"))
                == 0;
        Assumptions.assumeTrue(hintApplies, "이 배포판의 /etc/bash.bashrc 는 sudo 안내를 내지 않는다");

        // when
        Run run = interactive("/tmp", "exit\n");

        // then
        assertThat(count(run.text(), SUDO_HINT)).isEqualTo(1);
    }

    @Test
    @DisplayName("빈 홈(.bashrc 없음)에서도 bash-completion 은 그냥 로그인 셸을 띄울 때와 똑같이 불러와진다")
    void bashCompletionMatchesLoginShell() throws Exception {
        // given
        boolean completionInstalled = wslExecCode(List.of("test", "-r", "/etc/profile.d/bash_completion.sh")) == 0;
        Assumptions.assumeTrue(completionInstalled, "이 배포판에는 /etc/profile.d/bash_completion.sh 가 없다");
        String probe = "echo \"COMP<${BASH_COMPLETION_VERSINFO[*]-none}>\"\nexit\n";

        // when
        Run run = interactive("/tmp", probe);
        Outcome login = runWslWithInput(List.of("env", "HOME=" + home, "bash", "-l", "-i"), probe, Map.of());

        // then
        assertThat(marked(run.text(), "COMP")).isEqualTo(marked(login.text(), "COMP"));
    }

    @Test
    @DisplayName("/etc/profile 을 읽은 뒤의 PS1 은 그냥 로그인 셸을 띄울 때와 같다")
    void promptStringMatchesLoginShell() throws Exception {
        // given
        String probe = "echo \"PS1<$PS1>\"\nexit\n";

        // when
        Run run = interactive("/tmp", probe);
        Outcome login = runWslWithInput(List.of("env", "HOME=" + home, "bash", "-l", "-i"), probe, Map.of());

        // then
        assertThat(marked(run.text(), "PS1")).isNotEmpty().isEqualTo(marked(login.text(), "PS1"));
    }

    @Test
    @DisplayName("PS1 이 없는 비대화형 bash 에서 rcfile 을 source 해도 PS1 은 계속 없다")
    void unsetPromptStringStaysUnset() throws Exception {
        // given
        Path rcfile = installedIntegration(appData).directory().resolve("tanager.bash");
        writeHomeFile("probe-ps1.sh", ". \"$TANAGER_RC\"\necho \"PS1SET<${PS1+set}>\"\n");

        // when
        Outcome outcome = runWslWithInput(
                List.of("env", "-u", "PS1", "HOME=" + home, "bash", home + "/probe-ps1.sh"),
                "",
                Map.of("TANAGER_RC", rcfile.toString(), "WSLENV", "TANAGER_RC/p"));

        // then
        assertThat(marked(outcome.text(), "PS1SET")).isEmpty();
    }

    @Test
    @DisplayName("export 된 PROMPT_COMMAND 를 물려받은 중첩 bash 에서도 오류 없이 OSC 7 이 나온다")
    void exportedPromptCommandWorksInNestedBash() throws Exception {
        // given
        writeHomeFile(".profile", "export PROMPT_COMMAND='true'\n");

        // when
        Run run = interactive("/tmp", "bash -i\necho NESTED_OK\nexit\nexit\n");

        // then
        assertThat(run.text())
                .contains("NESTED_OK")
                .doesNotContain("__tanager_prompt")
                .doesNotContain("__tanager_osc7");
        assertThat(run.cwds()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("배열 PROMPT_COMMAND 의 사용자 훅과 우리 훅이 모두 돌고 사용자 훅은 직전 명령의 $? 를 본다")
    void arrayPromptCommandRunsAllHooksAndKeepsStatus() throws Exception {
        // given
        writeHomeFile(".profile", "PROMPT_COMMAND=('echo \"HOOK_A<$?>\"' 'echo HOOK_B')\n");

        // when
        Run run = interactive("/tmp", "false\nexit\n");

        // then
        assertThat(run.text()).contains("HOOK_A<0>", "HOOK_A<1>", "HOOK_B");
        assertThat(run.cwds()).isNotEmpty();
    }

    @Test
    @DisplayName("문자열 PROMPT_COMMAND 의 사용자 훅도 우리 훅 뒤에 직전 명령의 $? 를 그대로 본다")
    void stringPromptCommandKeepsStatus() throws Exception {
        // given
        writeHomeFile(".profile", "PROMPT_COMMAND='echo \"HOOK_S<$?>\"'\n");

        // when
        Run run = interactive("/tmp", "false\nexit\n");

        // then
        assertThat(run.text()).contains("HOOK_S<1>");
        assertThat(run.cwds()).isNotEmpty();
    }

    @Test
    @DisplayName("rcfile 을 다시 source 해도 우리 훅은 한 번만 들어 있다")
    void sourcingRcfileAgainDoesNotInstallHookTwice() throws Exception {
        // given / when
        Run run = interactive("/tmp", ". \"$TANAGER_RC\"\necho \"HOOKS<${PROMPT_COMMAND[*]}>\"\nexit\n");

        // then
        assertThat(count(marked(run.text(), "HOOKS"), "__tanager_prompt")).isEqualTo(1);
    }

    // ---- 실행 도우미 ----

    private record Run(String text, List<String> cwds) {}

    private Run interactive(String startDir, String stdin) throws Exception {
        return interactive(appData, startDir, stdin, Map.of());
    }

    private Run interactive(Path appDataDir, String startDir, String stdin) throws Exception {
        return interactive(appDataDir, startDir, stdin, Map.of());
    }

    /** 앱 데이터를 {@code appDataDir} 로 둔 모듈에서 셸 통합을 설치하고, 만들어진 기동 명령으로 WSL bash 를 띄워 {@code stdin} 을 먹인다. */
    private Run interactive(Path appDataDir, String startDir, String stdin, Map<String, String> envOverride)
            throws Exception {
        WindowsPlatformModule module = moduleWithAppData(appDataDir);
        Integration integration = installedIntegration(appDataDir);
        ShellEnvironment.Wsl env = new ShellEnvironment.Wsl(distro);
        ShellProfile profile = new ShellProfile(
                new ProfileId("wsl:" + distro),
                distro,
                ShellKind.BASH,
                env,
                new LaunchTemplate(WSL_EXE, List.of("-d", distro)),
                IntegrationMode.OSC7);
        WorkingDir dir =
                new WorkingDir.At(WorkspacePath.of(env, startDir).value().orElseThrow());

        LaunchCommand command = module.launchCommand(profile, dir, integration);

        // rcfile 이 있든 없든(물러선 bash -i) 같은 임시 홈을 쓰도록 bash 마다 앞에 끼운다
        List<String> argv = new ArrayList<>();
        for (String arg : command.argv()) {
            if (arg.equals("bash")) {
                argv.addAll(List.of("env", "HOME=" + home));
            }
            argv.add(arg);
        }
        assertThat(argv).as("argv 에 bash 가 있어야 한다: %s", command.argv()).contains("HOME=" + home);
        ProcessBuilder builder = new ProcessBuilder(argv).redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().putAll(command.environmentOver(System.getenv()));
        builder.environment().putAll(envOverride);
        builder.environment().put("WSL_UTF8", "1");
        Process process = builder.start();
        CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> readAll(process));
        process.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        if (!process.waitFor(SHELL_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("WSL bash 가 " + SHELL_TIMEOUT + " 안에 끝나지 않았다");
        }
        byte[] bytes = output.get(10, TimeUnit.SECONDS);
        List<String> cwds = new ArrayList<>();
        for (CwdReport report : OscScanner.initial().feed(bytes).reports()) {
            if (report instanceof CwdReport.FileUri uri) {
                cwds.add(uri.path());
            }
        }
        return new Run(new String(bytes, StandardCharsets.UTF_8), cwds);
    }

    private static WindowsPlatformModule moduleWithAppData(Path appDataDir) {
        WindowsShellCatalog catalog = new WindowsShellCatalog(
                new FakeCommandRunner(new CommandOutcome.NotStarted("x")),
                key -> Optional.empty(),
                Duration.ofSeconds(1));
        return new WindowsPlatformModule(
                catalog,
                key -> key.equals("APPDATA")
                        ? Optional.of(appDataDir.toString())
                        : Optional.ofNullable(System.getenv(key)),
                System.getProperty("user.home"));
    }

    private static Integration.Installed installedIntegration(Path appDataDir) {
        Integration integration = moduleWithAppData(appDataDir).integration().install();
        assertThat(integration).as("셸 통합 설치").isInstanceOf(Integration.Installed.class);
        return (Integration.Installed) integration;
    }

    private void mkdir(String path) throws Exception {
        createdOnWsl.add(path);
        assertThat(wslExecCode(List.of("mkdir", "-p", path))).isZero();
    }

    private void writeHomeFile(String name, String content) throws Exception {
        String encoded = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        int code =
                wslExecCode(List.of("bash", "-c", "echo \"$1\" | base64 -d > \"$2\"", "_", encoded, home + "/" + name));
        assertThat(code).as("%s 쓰기", name).isZero();
    }

    /** {@code <begin>...<end>} 모양의 표식 안 값. 없으면 실패한다. */
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

    private static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    private String wslExec(List<String> command) throws Exception {
        return runWsl(command).text();
    }

    private int wslExecCode(List<String> command) throws Exception {
        return runWsl(command).code();
    }

    private record Outcome(int code, String text) {}

    private Outcome runWsl(List<String> command) throws Exception {
        List<String> argv = new ArrayList<>(List.of(WSL_EXE, "-d", distro, "--exec"));
        argv.addAll(command);
        return runProcess(argv);
    }

    /** 대화형 로그인 셸처럼 표준 입력 · 환경을 줘야 하는 명령. 입력을 다 쓰면 닫는다. */
    private Outcome runWslWithInput(List<String> command, String stdin, Map<String, String> env) throws Exception {
        List<String> argv = new ArrayList<>(List.of(WSL_EXE, "-d", distro, "--exec"));
        argv.addAll(command);
        ProcessBuilder builder = new ProcessBuilder(argv).redirectErrorStream(true);
        builder.environment().putAll(env);
        builder.environment().put("WSL_UTF8", "1");
        Process process = builder.start();
        CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> readAll(process));
        process.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        if (!process.waitFor(SHELL_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("명령이 " + SHELL_TIMEOUT + " 안에 끝나지 않았다: " + argv);
        }
        return new Outcome(process.exitValue(), new String(output.get(10, TimeUnit.SECONDS), StandardCharsets.UTF_8));
    }

    private static Outcome runProcess(List<String> argv) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(argv).redirectErrorStream(true);
        builder.environment().put("WSL_UTF8", "1");
        Process process = builder.start();
        CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> readAll(process));
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("명령이 20초 안에 끝나지 않았다: " + argv);
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
        Outcome listed = runProcess(List.of(WSL_EXE, "--list", "--quiet"));
        Optional<String> found = listed.text()
                .replace("\u0000", "")
                .lines()
                .map(line -> line.replace("\uFEFF", "").trim())
                .filter(line -> !line.isEmpty() && !line.startsWith("docker-desktop"))
                .findFirst();
        Assumptions.assumeTrue(found.isPresent(), "설치된 WSL 배포판이 없다");
        return found.orElseThrow();
    }
}
