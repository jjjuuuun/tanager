package dev.tanager.platform.macos;

import dev.tanager.application.platform.OperatingSystem;
import dev.tanager.application.port.out.PlatformModule;

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
}
