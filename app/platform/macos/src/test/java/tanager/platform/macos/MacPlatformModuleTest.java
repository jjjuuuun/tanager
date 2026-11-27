package tanager.platform.macos;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ServiceLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.platform.OperatingSystem;
import tanager.application.port.out.Integration;
import tanager.application.port.out.PlatformModule;

class MacPlatformModuleTest {
    private final MacPlatformModule module = new MacPlatformModule();

    @Test
    @DisplayName("macOS 이름만 지원한다(Java 의 os.name 은 \"Mac OS X\")")
    void supportsOnlyMac() {
        assertThat(module.supports(new OperatingSystem("Mac OS X", "15.0", "aarch64")))
                .isTrue();
        assertThat(module.supports(new OperatingSystem("Windows 11", "10.0", "amd64")))
                .isFalse();
        assertThat(module.supports(new OperatingSystem("Linux", "6.6", "amd64")))
                .isFalse();
    }

    @Test
    @DisplayName("id 는 macos")
    void idIsMacos() {
        assertThat(module.id()).isEqualTo("macos");
    }

    @Test
    @DisplayName("ServiceLoader 로 찾을 수 있게 등록돼 있다")
    void isRegisteredAsService() {
        assertThat(ServiceLoader.load(PlatformModule.class).stream().map(ServiceLoader.Provider::type))
                .contains(MacPlatformModule.class);
    }

    @Test
    @DisplayName("shells() 는 M7 전까지 빈 목록이다")
    void shellsIsEmpty() {
        assertThat(module.shells()).isEmpty();
    }

    @Test
    @DisplayName("integration().install() 은 M7 전까지 예외 없이 Unavailable(NotSupported) 다")
    void integrationIsUnavailable() {
        // when
        Integration result = module.integration().install();

        // then
        assertThat(result).isEqualTo(new Integration.Unavailable(new Integration.Problem.NotSupported()));
    }

    @Test
    @DisplayName("paths() 는 같은 환경만 그대로 돌려준다(Posix 는 M7)")
    void pathsSameEnvironmentOnly() {
        // given
        var win = new tanager.domain.history.ShellEnvironment.Windows();
        var path = tanager.domain.history.WorkspacePath.of(win, "C:\\x").value().orElseThrow();

        // when / then
        org.assertj.core.api.Assertions.assertThat(
                        new MacPlatformModule().paths().translate(path, win))
                .contains(path);
        org.assertj.core.api.Assertions.assertThat(new MacPlatformModule()
                        .paths()
                        .translate(path, new tanager.domain.history.ShellEnvironment.Wsl("U")))
                .isEmpty();
    }
}
