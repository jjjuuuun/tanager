package dev.tanager.platform.macos;

import dev.tanager.application.platform.OperatingSystem;
import dev.tanager.application.port.out.Integration;
import dev.tanager.application.port.out.LaunchCommand;
import dev.tanager.application.port.out.PlatformDirs;
import dev.tanager.application.port.out.PlatformModule;
import dev.tanager.application.port.out.ShellIntegration;
import dev.tanager.domain.session.ShellProfile;
import dev.tanager.domain.session.WorkingDir;
import java.util.List;

/** macOS (M7 에서 채운다). Java 의 os.name 은 "Mac OS X". */
public final class MacPlatformModule implements PlatformModule {

    @Override
    public String id() {
        return "macos";
    }

    @Override
    public boolean supports(OperatingSystem os) {
        return os.name().startsWith("Mac");
    }

    /** M7 에서 채운다. 그때까지 빈 목록. */
    @Override
    public List<ShellProfile> shells() {
        return List.of();
    }

    /** M7 에서 채운다. 그때까지는 지원하지 않는다. */
    @Override
    public LaunchCommand launchCommand(ShellProfile profile, WorkingDir dir) {
        throw new UnsupportedOperationException("macOS 셸 기동은 M7 에서 지원한다");
    }

    /** M7 에서 채운다(~/Library/Application Support/Tanager). 그때까지는 지원하지 않는다. */
    @Override
    public PlatformDirs dirs() {
        throw new UnsupportedOperationException("macOS 앱 폴더는 M7 에서 지원한다");
    }

    /** M7 에서 zsh 판을 채운다. 그때까지 셸 통합 없음. */
    @Override
    public ShellIntegration integration() {
        return () -> new Integration.Unavailable("M7");
    }
}
