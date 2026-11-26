package dev.tanager.platform.windows;

import dev.tanager.application.platform.OperatingSystem;
import dev.tanager.application.port.out.Integration;
import dev.tanager.application.port.out.LaunchCommand;
import dev.tanager.application.port.out.PlatformDirs;
import dev.tanager.application.port.out.PlatformModule;
import dev.tanager.application.port.out.ShellIntegration;
import dev.tanager.domain.history.ShellEnvironment;
import dev.tanager.domain.history.WorkspacePath;
import dev.tanager.domain.session.ShellProfile;
import dev.tanager.domain.session.WorkingDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Windows 계열(10 · 11). WSL · PowerShell 5.1 · cmd 는 이 모듈이 맡는다(M2~). */
public final class WindowsPlatformModule implements PlatformModule {
    private static final Duration WSL_LIST_TIMEOUT = Duration.ofSeconds(3);
    private static final String APP_FOLDER = "Tanager";
    private static final Function<String, Optional<String>> SYSTEM_ENV =
            name -> Optional.ofNullable(System.getenv(name));

    private final WindowsShellCatalog shellCatalog;
    private final String userProfile;
    private final Optional<String> wslenv;
    private final Path appData;

    /** ServiceLoader 용. 실제 프로세스 실행과 시스템 환경변수를 쓴다. */
    public WindowsPlatformModule() {
        this(SYSTEM_ENV);
    }

    private WindowsPlatformModule(Function<String, Optional<String>> env) {
        this(new WindowsShellCatalog(new ProcessCommandRunner(), env, WSL_LIST_TIMEOUT), env);
    }

    /** 환경 변수({@code USERPROFILE} · {@code APPDATA} · {@code WSLENV})는 여기서 한 번 읽는다 — 기동 명령 계산을 순수하게 두려고. */
    WindowsPlatformModule(WindowsShellCatalog shellCatalog, Function<String, Optional<String>> env) {
        this(shellCatalog, env, System.getProperty("user.home"));
    }

    /**
     * {@code fallbackHome} 은 {@code USERPROFILE} 이 없거나 공백일 때의 홈이고, {@code APPDATA} 가 없을 때 {@code
     * <fallbackHome>\AppData\Roaming} 의 기준이다. 보통 {@code user.home}.
     */
    WindowsPlatformModule(
            WindowsShellCatalog shellCatalog, Function<String, Optional<String>> env, String fallbackHome) {
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
        return WindowsLaunchCommands.of(profile, dir, userProfile, wslenv, integration);
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
}
