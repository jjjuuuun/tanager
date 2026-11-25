package dev.tanager.platform.macos;

import dev.tanager.application.port.out.PlatformModule;
import dev.tanager.application.port.out.ShellCatalogContract;

/** macOS 모듈(M7 전까지 빈 목록)도 셸 목록 계약을 지킨다. */
class MacShellCatalogContractTest extends ShellCatalogContract {
    @Override
    protected PlatformModule platform() {
        return new MacPlatformModule();
    }
}
