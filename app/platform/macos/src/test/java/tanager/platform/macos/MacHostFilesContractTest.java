package tanager.platform.macos;

import tanager.application.port.out.HostFiles;
import tanager.application.port.out.HostFilesContract;
import tanager.domain.history.ShellEnvironment;

/** macOS 의 {@link HostFiles} 가 계약을 지킨다(#205). 환경 Posix 와 홈은 M7 에서 채운다. */
class MacHostFilesContractTest extends HostFilesContract {

    @Override
    protected HostFiles hostFiles() {
        return new MacPlatformModule().hostFiles();
    }

    @Override
    protected ShellEnvironment unknownEnvironment() {
        return new ShellEnvironment.Wsl("Ubuntu");
    }
}
