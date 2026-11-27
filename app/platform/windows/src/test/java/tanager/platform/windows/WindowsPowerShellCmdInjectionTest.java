package tanager.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.out.Integration;
import tanager.application.port.out.LaunchCommand;
import tanager.application.port.out.LaunchCommand.EnvNames;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.session.IntegrationMode;
import tanager.domain.session.LaunchTemplate;
import tanager.domain.session.ProfileId;
import tanager.domain.session.ShellKind;
import tanager.domain.session.ShellProfile;
import tanager.domain.session.WorkingDir;

/**
 * PowerShell 5.1 · cmd 에 셸 통합을 주입하는 기동 명령 계산(#81, ADR-0014). 순수 계산이라 OS 와 무관하게 돈다.
 *
 * <p>PowerShell 은 {@code -Command} 문자열에 사용자 데이터를 넣지 않고 환경 변수 {@code TANAGER_PS1} 로 스크립트 경로를 넘긴다. cmd
 * 는 {@code /k} 를 쓰지 않고 환경 변수 {@code PROMPT} 앞에 OSC 9;9 를 붙인다.
 */
class WindowsPowerShellCmdInjectionTest {
    private static final String PS_EXE = "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe";
    private static final String CMD_EXE = "C:\\Windows\\System32\\cmd.exe";
    private static final String USER_PROFILE = "C:\\Users\\me";
    private static final String PS_VARIABLE = "TANAGER_PS1";
    // 실행 정책을 바꾸지 않는다: 문자열로 만든 스크립트 블록에는 실행 정책이 적용되지 않는다. 파일이 없으면 조용히 건너뛴다
    private static final String PS_COMMAND = "if (Test-Path -LiteralPath $env:TANAGER_PS1) "
            + "{ . ([scriptblock]::Create([IO.File]::ReadAllText($env:TANAGER_PS1))) }";
    // cmd 의 $e 는 ESC, $P 는 현재 경로. OSC 9;9 의 따옴표는 cmd 쪽에서 붙이지 않는다(OscScanner 가 따옴표 없는 값도 읽는다)
    private static final String PROMPT_PREFIX = "$e]9;9;$P$e\\";
    private static final String DEFAULT_PROMPT = "$P$G";

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
    private static final ShellEnvironment UBUNTU = new ShellEnvironment.Wsl("Ubuntu");
    private static final ShellProfile WSL = new ShellProfile(
            new ProfileId("wsl:Ubuntu"),
            "Ubuntu",
            ShellKind.BASH,
            UBUNTU,
            new LaunchTemplate("C:\\Windows\\System32\\wsl.exe", List.of("-d", "Ubuntu")),
            IntegrationMode.OSC7);

    private static final Path INSTALLED_DIR = Path.of("integration-dir", "shell-integration");
    private static final Integration INSTALLED = new Integration.Installed(INSTALLED_DIR);
    private static final Integration UNAVAILABLE = new Integration.Unavailable("스크립트를 쓸 수 없다");

    private static LaunchCommand of(
            ShellProfile profile, WorkingDir dir, Optional<String> prompt, Integration integration) {
        return WindowsLaunchCommands.of(profile, dir, USER_PROFILE, Optional.empty(), prompt, integration);
    }

    private static WorkingDir windowsDir(String raw) {
        return new WorkingDir.At(
                WorkspacePath.of(new ShellEnvironment.Windows(), raw).value().orElseThrow());
    }

    private static String ps1Of(Path directory) {
        return directory.resolve("tanager.ps1").toString();
    }

    // ---- PowerShell ----

    @Test
    @DisplayName("셸 통합이 설치돼 있으면 PowerShell 은 실행 정책을 바꾸지 않고 환경 변수 TANAGER_PS1 의 스크립트를 스크립트 블록으로 dot-source 하며 띄운다")
    void installedPowerShellDotSourcesScriptFromEnvironment() {
        // given / when
        LaunchCommand command = of(POWERSHELL, windowsDir("C:\\work"), Optional.empty(), INSTALLED);

        // then
        assertThat(command.argv()).containsExactly(PS_EXE, "-NoLogo", "-NoExit", "-Command", PS_COMMAND);
        assertThat(command.argv()).noneMatch(arg -> arg.equalsIgnoreCase("-ExecutionPolicy"));
        assertThat(command.environment()).isEqualTo(Map.of(PS_VARIABLE, ps1Of(INSTALLED_DIR)));
        assertThat(command.envNames()).isEqualTo(EnvNames.CASE_INSENSITIVE);
    }

    @Test
    @DisplayName("PowerShell 의 작업 디렉터리는 계속 lpCurrentDirectory(workingDirectory)로 넘기고 홈이면 USERPROFILE 이다")
    void installedPowerShellKeepsWorkingDirectory() {
        // given / when
        LaunchCommand at = of(POWERSHELL, windowsDir("C:\\work"), Optional.empty(), INSTALLED);
        LaunchCommand home = of(POWERSHELL, new WorkingDir.Home(), Optional.empty(), INSTALLED);

        // then
        assertThat(at.workingDirectory()).contains("C:\\work");
        assertThat(home.workingDirectory()).contains(USER_PROFILE);
    }

    @Test
    @DisplayName("PowerShell -Command 문자열은 고정이라 작업 경로 · 앱 데이터 경로가 들어가지 않고 따옴표도 없다")
    void powerShellCommandStringCarriesNoUserData() {
        // given
        Path appData = Path.of("C:\\Users\\O'Neil 한글\\AppData\\Roaming\\Tanager", "shell-integration");

        // when
        LaunchCommand command = of(
                POWERSHELL, windowsDir("C:\\work\\it's 한글 #1"), Optional.empty(), new Integration.Installed(appData));

        // then
        List<String> argv = command.argv();
        assertThat(argv.get(argv.indexOf("-Command") + 1)).isEqualTo(PS_COMMAND);
        assertThat(argv).noneMatch(arg -> arg.contains("O'Neil") || arg.contains("한글") || arg.contains("'"));
        assertThat(argv).noneMatch(arg -> arg.contains("\""));
        assertThat(command.environment()).containsEntry(PS_VARIABLE, ps1Of(appData));
    }

    @Test
    @DisplayName("PowerShell -Command 문자열은 감싸지지만 \" 가 없고 \\ 로 끝나지 않아 방어 검사(requireSafeArgv)를 통과한다")
    void powerShellCommandPassesArgvDefence() {
        // given
        LaunchCommand command = of(POWERSHELL, windowsDir("C:\\work"), Optional.empty(), INSTALLED);

        // when / then
        assertThat(PS_COMMAND).contains(" ").doesNotContain("\"").doesNotEndWith("\\");
        assertThatCode(() -> WindowsLaunchCommands.requireSafeArgv(new ShellEnvironment.Windows(), command.argv()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("셸 통합이 없으면 PowerShell 은 M2 와 같다 — 템플릿 인자 그대로, 환경 변수 없음")
    void unavailablePowerShellIsUnchanged() {
        // given / when
        LaunchCommand command = of(POWERSHELL, windowsDir("C:\\work"), Optional.of("$P$G"), UNAVAILABLE);

        // then
        assertThat(command.argv()).containsExactly(PS_EXE, "-NoLogo");
        assertThat(command.workingDirectory()).contains("C:\\work");
        assertThat(command.environment()).isEmpty();
    }

    // ---- cmd ----

    @Test
    @DisplayName("셸 통합이 설치돼 있고 PROMPT 가 없으면 cmd 는 PROMPT=$e]9;9;$P$e\\$P$G 로 띄우고 /k 를 쓰지 않는다")
    void installedCmdWithoutPromptUsesDefaultPrompt() {
        // given / when
        LaunchCommand command = of(CMD, windowsDir("C:\\work"), Optional.empty(), INSTALLED);

        // then
        assertThat(command.argv()).containsExactly(CMD_EXE);
        assertThat(command.environment()).isEqualTo(Map.of("PROMPT", PROMPT_PREFIX + DEFAULT_PROMPT));
        assertThat(command.workingDirectory()).contains("C:\\work");
        assertThat(command.envNames()).isEqualTo(EnvNames.CASE_INSENSITIVE);
    }

    @Test
    @DisplayName("상속한 PROMPT 가 있으면 cmd 는 그 앞에 OSC 9;9 를 붙여 사용자 PROMPT 를 지우지 않는다")
    void installedCmdPrependsToInheritedPrompt() {
        // given / when
        LaunchCommand command = of(CMD, new WorkingDir.Home(), Optional.of("[$T] $P$G"), INSTALLED);

        // then
        assertThat(command.environment()).isEqualTo(Map.of("PROMPT", PROMPT_PREFIX + "[$T] $P$G"));
        assertThat(command.workingDirectory()).contains(USER_PROFILE);
    }

    @Test
    @DisplayName("상속한 PROMPT 가 공백뿐이면 없는 것으로 보고 $P$G 를 쓴다")
    void blankInheritedPromptFallsBackToDefault() {
        // given / when
        LaunchCommand command = of(CMD, new WorkingDir.Home(), Optional.of("   "), INSTALLED);

        // then
        assertThat(command.environment()).isEqualTo(Map.of("PROMPT", PROMPT_PREFIX + DEFAULT_PROMPT));
    }

    @Test
    @DisplayName("상속한 PROMPT 가 이미 우리 접두로 시작하면 대소문자와 무관하게 다시 붙이지 않는다(Tanager 안에서 Tanager)")
    void alreadyPrefixedPromptIsNotPrefixedAgain() {
        for (String inherited : List.of(PROMPT_PREFIX + "[$T] $P$G", "$E]9;9;$p$E\\" + DEFAULT_PROMPT)) {
            // given / when
            LaunchCommand command = of(CMD, new WorkingDir.Home(), Optional.of(inherited), INSTALLED);

            // then
            assertThat(command.environment()).isEqualTo(Map.of("PROMPT", inherited));
        }
    }

    @Test
    @DisplayName("셸 통합이 없으면 cmd 는 M2 와 같다 — 실행 파일만, 환경 변수 없음(상속한 PROMPT 도 건드리지 않는다)")
    void unavailableCmdIsUnchanged() {
        // given / when
        LaunchCommand command = of(CMD, windowsDir("C:\\work"), Optional.of("[$T] $P$G"), UNAVAILABLE);

        // then
        assertThat(command.argv()).containsExactly(CMD_EXE);
        assertThat(command.workingDirectory()).contains("C:\\work");
        assertThat(command.environment()).isEmpty();
    }

    @Test
    @DisplayName("cmd 의 PROMPT 에는 앱 데이터 경로가 들어가지 않는다")
    void cmdPromptDoesNotMentionIntegrationDirectory() {
        // given
        Path appData = Path.of("C:\\Users\\O'Neil 한글\\AppData\\Roaming\\Tanager", "shell-integration");

        // when
        LaunchCommand command = of(CMD, new WorkingDir.Home(), Optional.empty(), new Integration.Installed(appData));

        // then
        assertThat(command.environment().values()).noneMatch(v -> v.contains("O'Neil") || v.contains("한글"));
        assertThat(command.argv()).containsExactly(CMD_EXE);
    }

    @Test
    @DisplayName("WSL 은 PROMPT 에 영향받지 않는다 — 환경은 TANAGER_RC · WSLENV 뿐이다")
    void wslIgnoresPrompt() {
        // given / when
        LaunchCommand command = of(WSL, new WorkingDir.Home(), Optional.of("[$T] $P$G"), INSTALLED);

        // then
        assertThat(command.environment()).containsOnlyKeys("TANAGER_RC", "WSLENV");
    }

    // ---- 속성 ----

    @Provide
    Arbitrary<String> unusualNames() {
        // ' · 공백 · 한글 · U+2018–U+201B(PowerShell 이 작은따옴표로 취급하는 따옴표들)
        return Arbitraries.strings()
                .withChars(' ', '\'', '\u2018', '\u2019', '\u201A', '\u201B', '한', '글', 'a', 'Z', '1', '#', '$', '%')
                .ofMinLength(3)
                .ofMaxLength(24);
    }

    @Property
    @Label("앱 데이터 경로가 어떤 모양이든 PowerShell argv · cmd argv · PROMPT 어디에도 나타나지 않고 TANAGER_PS1 에만 그대로 있다")
    void appDataPathNeverLeaksIntoArgvOrPrompt(@ForAll("unusualNames") String name) {
        // given
        Path directory = Path.of("C:\\Users", "[" + name + "]", "shell-integration");
        Integration installed = new Integration.Installed(directory);

        // when
        LaunchCommand powershell = of(POWERSHELL, new WorkingDir.Home(), Optional.empty(), installed);
        LaunchCommand cmd = of(CMD, new WorkingDir.Home(), Optional.empty(), installed);

        // then
        String marker = "[" + name + "]";
        assertThat(powershell.argv()).noneMatch(arg -> arg.contains(marker));
        assertThat(powershell.argv()).contains(PS_COMMAND);
        assertThat(powershell.environment()).isEqualTo(Map.of(PS_VARIABLE, ps1Of(directory)));
        assertThat(cmd.argv()).noneMatch(arg -> arg.contains(marker));
        assertThat(cmd.environment().values()).noneMatch(value -> value.contains(marker));
    }

    @Provide
    Arbitrary<String> prompts() {
        Arbitrary<String> random = Arbitraries.strings()
                .withChars(' ', '$', 'P', 'G', 'p', 'e', 'E', '[', ']', '\\', '9', ';', '한', '#')
                .ofMaxLength(24);
        // 무작위 글자만으로는 접두로 시작하는 값이 거의 나오지 않는다 — 이미 접두가 붙은 값(대소문자를 섞어서)도 섞는다
        Arbitrary<String> prefixed = random.map(rest -> PROMPT_PREFIX + rest);
        Arbitrary<String> upperPrefixed = random.map(rest -> PROMPT_PREFIX.toUpperCase(Locale.ROOT) + rest);
        return Arbitraries.oneOf(random, prefixed, upperPrefixed);
    }

    @Property
    @Label("PROMPT 병합은 접두를 한 번만 붙이고(기존이 접두로 시작하면 그대로), 병합한 값을 다시 병합해도 같다")
    void promptMergeIsIdempotentAndPreservesUserPrompt(@ForAll("prompts") String inherited) {
        // given / when
        String once = promptOf(Optional.of(inherited));
        String twice = promptOf(Optional.of(once));

        // then
        boolean blank = inherited.isBlank();
        boolean prefixed = inherited.regionMatches(true, 0, PROMPT_PREFIX, 0, PROMPT_PREFIX.length());
        if (blank) {
            assertThat(once).isEqualTo(PROMPT_PREFIX + DEFAULT_PROMPT);
        } else if (prefixed) {
            assertThat(once).isEqualTo(inherited);
        } else {
            assertThat(once).isEqualTo(PROMPT_PREFIX + inherited);
        }
        assertThat(twice).isEqualTo(once);
    }

    private static String promptOf(Optional<String> inherited) {
        return of(CMD, new WorkingDir.Home(), inherited, INSTALLED)
                .environment()
                .get("PROMPT");
    }

    // ---- 모듈: 상속 PROMPT 는 생성할 때 한 번 읽는다 ----

    private static WindowsPlatformModule module(java.util.function.Function<String, Optional<String>> env) {
        WindowsShellCatalog catalog = new WindowsShellCatalog(
                new FakeCommandRunner(new CommandOutcome.NotStarted("x")),
                key -> Optional.empty(),
                Duration.ofSeconds(1));
        return new WindowsPlatformModule(catalog, env);
    }

    @Test
    @DisplayName("모듈은 만들 때 환경의 PROMPT 를 한 번 읽어 cmd 기동 명령에 병합하고 이후 바뀐 값은 보지 않는다")
    void moduleReadsPromptOnceAtCreation() {
        // given
        AtomicReference<String> prompt = new AtomicReference<>("[$T] $P$G");
        WindowsPlatformModule module =
                module(key -> key.equals("PROMPT") ? Optional.ofNullable(prompt.get()) : Optional.empty());
        prompt.set("바뀐 값");

        // when
        LaunchCommand command = module.launchCommand(CMD, new WorkingDir.Home(), INSTALLED);

        // then
        assertThat(command.environment()).isEqualTo(Map.of("PROMPT", PROMPT_PREFIX + "[$T] $P$G"));
    }

    @Test
    @DisplayName("모듈에 PROMPT 가 없으면 cmd 는 $P$G 에 접두를 붙이고, 셸 통합이 없으면 환경을 건드리지 않는다")
    void moduleWithoutPromptUsesDefault() {
        // given
        WindowsPlatformModule module = module(key -> Optional.empty());

        // when
        LaunchCommand installed = module.launchCommand(CMD, new WorkingDir.Home(), INSTALLED);
        LaunchCommand unavailable = module.launchCommand(CMD, new WorkingDir.Home(), UNAVAILABLE);

        // then
        assertThat(installed.environment()).isEqualTo(Map.of("PROMPT", PROMPT_PREFIX + DEFAULT_PROMPT));
        assertThat(unavailable.environment()).isEmpty();
    }

    @Test
    @DisplayName("모듈 경유로도 PowerShell 은 설치 디렉터리의 tanager.ps1 을 TANAGER_PS1 로 넘긴다")
    void modulePassesPowerShellScriptThroughEnvironment() {
        // given
        WindowsPlatformModule module = module(key -> Optional.empty());

        // when
        LaunchCommand command = module.launchCommand(POWERSHELL, new WorkingDir.Home(), INSTALLED);

        // then
        assertThat(command.argv()).endsWith("-Command", PS_COMMAND);
        assertThat(command.environment()).isEqualTo(Map.of(PS_VARIABLE, ps1Of(INSTALLED_DIR)));
    }
}
