package tanager.platform.macos;

import tanager.application.port.out.PlatformModule;
import tanager.application.port.out.ShellCatalogContract;

/** macOS 모듈(M7 전까지 빈 목록)도 셸 목록 계약을 지킨다. 빈 목록이라 id · 이름 검사는 M7 전까지 공허하다. */
class MacShellCatalogContractTest extends ShellCatalogContract {
    @Override
    protected PlatformModule platform() {
        return new MacPlatformModule();
    }
}
