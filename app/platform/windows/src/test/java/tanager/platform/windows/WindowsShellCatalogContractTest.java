package tanager.platform.windows;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import tanager.application.port.out.PlatformModule;
import tanager.application.port.out.ShellCatalogContract;

/** 가짜 wsl.exe 출력(배포판 둘)으로 Windows 모듈이 셸 목록 계약을 지키는지 본다. */
class WindowsShellCatalogContractTest extends ShellCatalogContract {
    private static final Function<String, Optional<String>> ENV = key -> Optional.ofNullable(
            Map.of("SystemRoot", "C:\\Windows", "USERPROFILE", "C:\\Users\\me").get(key));

    @Override
    protected PlatformModule platform() {
        CommandOutcome twoDistros =
                new CommandOutcome.Exited(0, "Ubuntu-24.04\r\nDebian\r\n".getBytes(StandardCharsets.UTF_16LE));
        WindowsShellCatalog catalog =
                new WindowsShellCatalog(new FakeCommandRunner(twoDistros), ENV, Duration.ofSeconds(3));
        return new WindowsPlatformModule(catalog, ENV);
    }
}
