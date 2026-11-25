package dev.tanager.platform.windows;

import dev.tanager.application.platform.OperatingSystem;
import dev.tanager.application.port.out.LaunchCommand;
import dev.tanager.application.port.out.PlatformModule;
import dev.tanager.domain.history.WorkspacePath;
import dev.tanager.domain.session.ShellProfile;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Windows 계열(10 · 11). WSL · PowerShell 5.1 · cmd 는 이 모듈이 맡는다(M2~). */
public final class WindowsPlatformModule implements PlatformModule {
    private static final Duration WSL_LIST_TIMEOUT = Duration.ofSeconds(3);

    private final WindowsShellCatalog shellCatalog;

    /** ServiceLoader 용. 실제 프로세스 실행과 시스템 환경변수를 쓴다. */
    public WindowsPlatformModule() {
        this(new WindowsShellCatalog(
                new ProcessCommandRunner(), name -> Optional.ofNullable(System.getenv(name)), WSL_LIST_TIMEOUT));
    }

    WindowsPlatformModule(WindowsShellCatalog shellCatalog) {
        this.shellCatalog = Objects.requireNonNull(shellCatalog, "shellCatalog");
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
    public LaunchCommand launchCommand(ShellProfile profile, WorkspacePath cwd) {
        return WindowsLaunchCommands.of(profile, cwd);
    }
}
