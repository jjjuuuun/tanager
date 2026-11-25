package dev.tanager.platform.macos;

import dev.tanager.application.platform.OperatingSystem;
import dev.tanager.application.port.out.PlatformModule;
import dev.tanager.domain.session.ShellProfile;
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
}
