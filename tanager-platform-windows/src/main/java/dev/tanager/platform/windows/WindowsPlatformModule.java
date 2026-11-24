package dev.tanager.platform.windows;

import dev.tanager.application.platform.OperatingSystem;
import dev.tanager.application.port.out.PlatformModule;

/** Windows 계열(10 · 11). WSL · PowerShell 5.1 · cmd 는 이 모듈이 맡는다(M2~). */
public final class WindowsPlatformModule implements PlatformModule {

    @Override
    public String id() {
        return "windows";
    }

    @Override
    public boolean supports(OperatingSystem os) {
        return os.name().startsWith("Windows");
    }
}
