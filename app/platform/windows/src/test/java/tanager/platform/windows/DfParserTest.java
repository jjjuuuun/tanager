package tanager.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.out.ProbeError;
import tanager.domain.monitor.Usage;

/** WSL {@code df -kP /} 읽기(FR-MON-02, #187). 1K 블록 단위다. */
class DfParserTest {

    @Test
    @DisplayName("WSL 2.5 실제 출력(meminfo 뒤의 df)에서 / 의 Used · 1024-blocks 를 바이트로 읽는다")
    void parsesRealWslOutput() {
        // given
        String output = MeminfoParserTest.fixture("wsl-ubuntu-24.04.txt");

        // when / then
        assertThat(DfParser.parse(output).value()).contains(new Usage(134125748L * 1024, 1055762868L * 1024));
    }

    @Test
    @DisplayName("-P 출력은 장치 이름이 길어도 한 줄이다")
    void longDeviceName() {
        // given
        String output = MeminfoParserTest.fixture("df-long-device.txt");

        // when / then
        assertThat(DfParser.parse(output).value()).contains(new Usage(950186581L * 1024, 1055762868L * 1024));
    }

    @Test
    @DisplayName("머리줄이 지역화돼 있어도(ko_KR) 데이터 줄을 읽는다")
    void localizedHeader() {
        // given
        String output = "파일 시스템     1024-블록      사용      가용 용량 마운트위치\n"
                + "/dev/sdd       1055762868 134121112 867938284  14% /\n";

        // when / then
        assertThat(DfParser.parse(output).value()).contains(new Usage(134121112L * 1024, 1055762868L * 1024));
    }

    @Test
    @DisplayName("데이터 줄이 없으면(머리줄만) Unreadable 이다")
    void missingDataLine() {
        // when
        var result = DfParser.parse("Filesystem     1024-blocks      Used Available Capacity Mounted on\n");

        // then
        assertThat(result.error()).containsInstanceOf(ProbeError.Unreadable.class);
    }

    @Test
    @DisplayName("크기가 0 이면 Unreadable, Used 가 크기보다 크면 크기로 자른다")
    void zeroSizeOrOverfull() {
        assertThat(DfParser.parse("none 0 0 0 - /\n").error()).containsInstanceOf(ProbeError.Unreadable.class);
        assertThat(DfParser.parse("none 10 12 0 100% /\n").value()).contains(new Usage(10 * 1024, 10 * 1024));
    }
}
