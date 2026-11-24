package dev.tanager.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tanager.application.platform.OperatingSystem;
import dev.tanager.application.port.out.PlatformModule;
import java.util.ServiceLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WindowsPlatformModuleTest {
    private final WindowsPlatformModule module = new WindowsPlatformModule();

    @Test
    @DisplayName("Windows 계열 OS 이름만 지원한다")
    void supportsOnlyWindows() {
        assertThat(module.supports(new OperatingSystem("Windows 11", "10.0", "amd64")))
                .isTrue();
        assertThat(module.supports(new OperatingSystem("Windows 10", "10.0", "amd64")))
                .isTrue();
        assertThat(module.supports(new OperatingSystem("Linux", "6.6", "amd64")))
                .isFalse();
        assertThat(module.supports(new OperatingSystem("Mac OS X", "15.0", "aarch64")))
                .isFalse();
    }

    @Test
    @DisplayName("id 는 windows")
    void idIsWindows() {
        assertThat(module.id()).isEqualTo("windows");
    }

    @Test
    @DisplayName("ServiceLoader 로 찾을 수 있게 등록돼 있다")
    void isRegisteredAsService() {
        assertThat(ServiceLoader.load(PlatformModule.class).stream().map(ServiceLoader.Provider::type))
                .contains(WindowsPlatformModule.class);
    }
}
