package tanager.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import tanager.application.port.out.ResourceProbe;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.monitor.HostUsage;

/** 실제 Windows 에서 JDK · FileStore · wsl.exe 로 잰다(#187). */
@EnabledOnOs(OS.WINDOWS)
class WindowsResourceProbeIntegrationTest {
    private final ResourceProbe probe = new WindowsPlatformModule().resources();

    @Test
    @Tag("windows")
    @DisplayName("host() 의 메모리 전체가 0 보다 크고 CPU 사용률이 0~1 이다")
    void host() {
        // when
        HostUsage host = probe.host();

        // then
        assertThat(host.memory().total()).isPositive();
        assertThat(host.cpuLoad()).isBetween(0.0, 1.0);
        assertThat(host.cores()).isPositive();
    }

    @Test
    @Tag("windows")
    @DisplayName("C:\\ 의 디스크를 잰다")
    void systemDrive() {
        // when / then
        assertThat(probe.disk(WorkspacePath.of(new ShellEnvironment.Windows(), "C:\\")
                        .value()
                        .orElseThrow()))
                .hasValueSatisfying(disk -> assertThat(disk.usage().total()).isPositive());
    }

    @Test
    @Tag("wsl")
    @DisplayName("기본 배포판의 VM 메모리와 / 디스크를 5초 안에 읽는다")
    void wsl() {
        // given
        String distro = new WindowsPlatformModule()
                .shells().stream()
                        .map(profile -> profile.env())
                        .filter(env -> env instanceof ShellEnvironment.Wsl)
                        .map(env -> ((ShellEnvironment.Wsl) env).distro())
                        .findFirst()
                        .orElseThrow();

        // when / then
        assertThat(probe.wsl(distro, Duration.ofSeconds(5)).value())
                .hasValueSatisfying(
                        usage -> assertThat(usage.vmMemory().total()).isPositive());
    }
}
