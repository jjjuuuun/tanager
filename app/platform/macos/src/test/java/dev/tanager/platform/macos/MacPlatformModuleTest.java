package dev.tanager.platform.macos;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tanager.application.platform.OperatingSystem;
import dev.tanager.application.port.out.PlatformModule;
import java.util.ServiceLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
