package tanager.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WslListParserTest {
    private static byte[] utf16le(String s) {
        return s.getBytes(StandardCharsets.UTF_16LE);
    }

    @Test
    @DisplayName("UTF-16LE 출력에서 배포판 이름을 출력 순서대로 뽑는다")
    void parsesUtf16le() {
        // given
        byte[] out = utf16le("Ubuntu-24.04\r\nDebian\r\n");
        // when / then
        assertThat(WslListParser.parse(out)).containsExactly("Ubuntu-24.04", "Debian");
    }

    @Test
    @DisplayName("BOM · 빈 줄 · 앞뒤 공백 · 끝 NUL 을 정리한다")
    void cleansNoise() {
        // given
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xFE});
        b.writeBytes(utf16le("  Ubuntu  \r\n\r\n\tDebian \r\n\r\n"));
        b.writeBytes(new byte[] {0, 0});
        // when / then
        assertThat(WslListParser.parse(b.toByteArray())).containsExactly("Ubuntu", "Debian");
    }

    @Test
    @DisplayName("UTF-8 출력(WSL_UTF8=1)도 읽는다")
    void parsesUtf8() {
        assertThat(WslListParser.parse("Ubuntu\n".getBytes(StandardCharsets.UTF_8)))
                .containsExactly("Ubuntu");
    }

    @Test
    @DisplayName("UTF-8 BOM 이 있어도 이름에 섞이지 않는다")
    void parsesUtf8WithBom() {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.writeBytes(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        b.writeBytes("Ubuntu\r\n".getBytes(StandardCharsets.UTF_8));
        assertThat(WslListParser.parse(b.toByteArray())).containsExactly("Ubuntu");
    }

    @Test
    @DisplayName("빈 출력은 빈 목록이다")
    void emptyOutput() {
        assertThat(WslListParser.parse(new byte[0])).isEqualTo(List.of());
    }

    @Test
    @DisplayName("중복 이름은 처음 것만 남기고 대소문자는 유지한다")
    void dedupesKeepingCase() {
        assertThat(WslListParser.parse(utf16le("Ubuntu\r\nubuntu\r\nUbuntu\r\n")))
                .containsExactly("Ubuntu", "ubuntu");
    }

    @Test
    @DisplayName("UTF-16LE 끝에 남은 홀수 바이트는 U+FFFD 이름을 만들지 않는다")
    void oddTrailingByte() {
        // given
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.writeBytes(utf16le("Ubuntu\r\n"));
        b.write(0x41);
        // when / then
        assertThat(WslListParser.parse(b.toByteArray())).containsExactly("Ubuntu");
    }

    @Test
    @DisplayName("UTF-16BE BOM 입력에서 쓰레기 이름을 만들지 않는다")
    void utf16beIsNotMisread() {
        // given
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.writeBytes(new byte[] {(byte) 0xFE, (byte) 0xFF});
        b.writeBytes("Ubuntu\r\n".getBytes(StandardCharsets.UTF_16BE));
        // when / then
        assertThat(WslListParser.parse(b.toByteArray()))
                .allSatisfy(name -> assertThat(name).matches("[A-Za-z0-9._-]+"));
    }

    @Test
    @DisplayName("종료 코드 0 으로 나온 공백 포함 안내 문구는 배포판 이름이 아니다")
    void dropsGuidanceMessages() {
        // given
        byte[] out = utf16le("Windows Subsystem for Linux has no installed distributions.\r\n"
                + "Linux용 Windows 하위 시스템에 설치된 배포판이 없습니다.\r\n");
        // when / then
        assertThat(WslListParser.parse(out)).isEmpty();
    }
}
