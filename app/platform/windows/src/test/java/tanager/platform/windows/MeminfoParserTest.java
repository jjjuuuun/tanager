package tanager.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.out.ProbeError;
import tanager.domain.monitor.Usage;

/** WSL {@code /proc/meminfo} 읽기(FR-MON-02, #187). */
class MeminfoParserTest {

    static String fixture(String name) {
        try (InputStream in = MeminfoParserTest.class.getResourceAsStream("/monitor/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("WSL 2.5 실제 출력(meminfo + df)에서 used = MemTotal − MemAvailable, total = MemTotal(바이트)이다")
    void parsesRealWslOutput() {
        // given: memory=11GB 인 .wslconfig 아래 Ubuntu 24.04 — MemTotal 이 VM 한도를 반영한다
        String output = fixture("wsl-ubuntu-24.04.txt");

        // when / then
        assertThat(MeminfoParser.parse(output).value())
                .contains(new Usage((11216076L - 2578028L) * 1024, 11216076L * 1024));
    }

    @Test
    @DisplayName("MemAvailable 이 없으면 Unreadable 이다")
    void missingFieldIsAnError() {
        // when
        var result = MeminfoParser.parse("MemTotal:       11216076 kB\nMemFree:          271320 kB\n");

        // then
        assertThat(result.error()).containsInstanceOf(ProbeError.Unreadable.class);
    }

    @Test
    @DisplayName("값이 숫자가 아니거나 MemTotal 이 0 이면 Unreadable 이다")
    void malformedValueIsAnError() {
        assertThat(MeminfoParser.parse("MemTotal: x kB\nMemAvailable: 1 kB\n").error())
                .containsInstanceOf(ProbeError.Unreadable.class);
        assertThat(MeminfoParser.parse("MemTotal: 0 kB\nMemAvailable: 0 kB\n").error())
                .containsInstanceOf(ProbeError.Unreadable.class);
    }

    @Test
    @DisplayName("MemAvailable 이 MemTotal 보다 크면 사용량은 0 이다")
    void availableAboveTotalClampsToZero() {
        assertThat(MeminfoParser.parse("MemTotal: 10 kB\r\nMemAvailable: 12 kB\r\n")
                        .value())
                .contains(new Usage(0, 10 * 1024));
    }
}
