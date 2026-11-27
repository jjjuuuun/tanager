package tanager.platform.windows;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Function;
import tanager.application.platform.OperatingSystem;
import tanager.application.port.out.DirectoryProbe;
import tanager.application.port.out.Integration;
import tanager.application.port.out.LaunchCommand;
import tanager.application.port.out.PathTranslation;
import tanager.application.port.out.PlatformDirs;
import tanager.application.port.out.PlatformModule;
import tanager.application.port.out.ResourceProbe;
import tanager.application.port.out.ShellIntegration;
import tanager.application.port.out.SystemAppearance;
import tanager.application.port.out.SystemTools;
import tanager.domain.history.PathTranslator;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.session.ShellProfile;
import tanager.domain.session.WorkingDir;

/** Windows 계열(10 · 11). WSL · PowerShell 5.1 · cmd 는 이 모듈이 맡는다(M2~). */
public final class WindowsPlatformModule implements PlatformModule {
    private static final Duration WSL_LIST_TIMEOUT = Duration.ofSeconds(3);
    // 시작 화면 전에 한 번 읽는다 — reg.exe 는 보통 수십 ms 라 길게 기다리지 않는다
    private static final Duration APPEARANCE_TIMEOUT = Duration.ofSeconds(1);
    private static final String APP_FOLDER = "Tanager";
    private static final Function<String, Optional<String>> SYSTEM_ENV =
            name -> Optional.ofNullable(System.getenv(name));
    // 파일 확인은 블로킹이고 시간을 넘기면 버린다 — 버린 확인이 쌓여도 플랫폼 스레드를 잡지 않게 가상 스레드에서
    private static final Executor PROBE_THREADS =
            task -> Thread.ofVirtual().name("tanager-directory-probe").start(task);

    private final WindowsShellCatalog shellCatalog;
    private final String userProfile;
    private final Optional<String> wslenv;
    private final Optional<String> prompt;
    private final Path appData;
    private final CommandRunner appearanceRunner;
    private final WslMountRoots mounts = new WslMountRoots();
    private final DirectoryProbe directories;

    /** ServiceLoader 용. 실제 프로세스 실행과 시스템 환경변수를 쓴다. */
    public WindowsPlatformModule() {
        this(SYSTEM_ENV);
    }

    private WindowsPlatformModule(Function<String, Optional<String>> env) {
        this(new WindowsShellCatalog(new ProcessCommandRunner(), env, WSL_LIST_TIMEOUT), env);
    }

    /**
     * 환경 변수({@code USERPROFILE} · {@code APPDATA} · {@code WSLENV} · {@code PROMPT})는 여기서 한 번 읽는다 — 기동 명령 계산을 순수하게
     * 두려고.
     */
    WindowsPlatformModule(WindowsShellCatalog shellCatalog, Function<String, Optional<String>> env) {
        this(shellCatalog, env, System.getProperty("user.home"));
    }

    /**
     * {@code fallbackHome} 은 {@code USERPROFILE} 이 없거나 공백일 때의 홈이고, {@code APPDATA} 가 없을 때 {@code
     * <fallbackHome>\AppData\Roaming} 의 기준이다. 보통 {@code user.home}.
     */
    WindowsPlatformModule(
            WindowsShellCatalog shellCatalog, Function<String, Optional<String>> env, String fallbackHome) {
        this(shellCatalog, env, fallbackHome, new ProcessCommandRunner());
    }

    /** {@code appearanceRunner} 는 {@link #appearance()} 가 레지스트리를 읽을 때 쓴다. */
    WindowsPlatformModule(
            WindowsShellCatalog shellCatalog,
            Function<String, Optional<String>> env,
            String fallbackHome,
            CommandRunner appearanceRunner) {
        this(shellCatalog, env, fallbackHome, appearanceRunner, new NioWindowsFiles(), PROBE_THREADS);
    }

    /** {@code files} 와 {@code probeExecutor} 는 {@link #directories()} 가 경로를 확인할 때 쓴다. */
    WindowsPlatformModule(
            WindowsShellCatalog shellCatalog,
            Function<String, Optional<String>> env,
            String fallbackHome,
            CommandRunner appearanceRunner,
            WindowsFiles files,
            Executor probeExecutor) {
        this.directories = new WindowsDirectoryProbe(files, mounts, probeExecutor);
        this.appearanceRunner = Objects.requireNonNull(appearanceRunner, "appearanceRunner");
        this.shellCatalog = Objects.requireNonNull(shellCatalog, "shellCatalog");
        Objects.requireNonNull(env, "env");
        Objects.requireNonNull(fallbackHome, "fallbackHome");
        // 슬래시 경로는 역슬래시로 정규화하고, 절대 경로가 아니면 대체 홈을 쓴다 — LaunchCommand 가 상대 경로를 거부하므로
        this.userProfile = nonBlank(env, "USERPROFILE")
                .flatMap(value ->
                        WorkspacePath.of(new ShellEnvironment.Windows(), value).value())
                .map(WorkspacePath::path)
                .orElse(fallbackHome);
        // WSL 셸에 rcfile 경로를 넘길 때 사용자의 WSLENV 를 보존하려고 읽는다(ADR-0014). 병합은 기동 명령 계산이 한다
        this.wslenv = env.apply("WSLENV");
        // cmd 에 OSC 9;9 를 줄 때 사용자의 PROMPT 를 지우지 않고 앞에 붙이려고 읽는다(ADR-0014). 병합은 기동 명령 계산이 한다
        this.prompt = env.apply("PROMPT");
        this.appData = nonBlank(env, "APPDATA")
                .map(dir -> Path.of(dir, APP_FOLDER))
                .orElseGet(() -> Path.of(fallbackHome, "AppData", "Roaming", APP_FOLDER));
    }

    private static Optional<String> nonBlank(Function<String, Optional<String>> env, String name) {
        return env.apply(name).map(String::strip).filter(value -> !value.isEmpty());
    }

    @Override
    public String id() {
        return "windows";
    }

    @Override
    public boolean supports(OperatingSystem os) {
        return os.name().startsWith("Windows");
    }

    @Override
    public List<ShellProfile> shells() {
        return shellCatalog.shells();
    }

    @Override
    public LaunchCommand launchCommand(ShellProfile profile, WorkingDir dir, Integration integration) {
        Objects.requireNonNull(integration, "integration");
        return WindowsLaunchCommands.of(profile, dir, userProfile, wslenv, prompt, integration);
    }

    @Override
    public PlatformDirs dirs() {
        Path dir = appData;
        return () -> dir;
    }

    @Override
    public ShellIntegration integration() {
        return new WindowsShellIntegration(appData);
    }

    /**
     * 배포판별 마운트 루트로 바꾼다. 루트는 {@link #directories()} 가 그 배포판에 닿았을 때 wsl.conf 에서 읽어 두고(#53), 아직 모르는
     * 배포판은 기본 /mnt/ 다. 돌려준 변환기는 부를 때마다 지금 아는 값을 본다.
     */
    @Override
    public PathTranslation paths() {
        return (from, to) -> PathTranslator.translate(from, to, mounts.snapshot());
    }

    /** 배포판에 닿으면(VM 을 깨운다) 그 배포판의 마운트 루트도 읽어 둔다. 같은 인스턴스를 돌려준다. */
    @Override
    public DirectoryProbe directories() {
        return directories;
    }

    /** '애니메이션 효과' 설정을 레지스트리에서 읽는다(#152). 부를 때마다 reg.exe 를 실행한다. */
    @Override
    public SystemAppearance appearance() {
        return WindowsAppearance.read(appearanceRunner, APPEARANCE_TIMEOUT);
    }

    /** JDK · FileStore · wsl.exe 로 잰다(#187). 부를 때마다 새 수집기다 — 상태가 없다. */
    @Override
    public ResourceProbe resources() {
        return WindowsResourceProbe.system(new ProcessCommandRunner(), paths());
    }

    /** 작업 관리자 · 디스크 정리(#187). */
    @Override
    public SystemTools systemTools() {
        return WindowsSystemTools.system();
    }
}
