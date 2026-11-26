package dev.tanager.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 배포 스크립트의 인코딩 · 줄바꿈 계약. 틀리면 PowerShell 5.1 이 한글을 깨고 bash 가 {@code $'\r'} 로 죽는다. */
class ShellIntegrationResourcesTest {

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = ShellIntegrationResourcesTest.class.getResourceAsStream("/shell-integration/" + name)) {
            assertThat(in).as("리소스 " + name).isNotNull();
            return in.readAllBytes();
        }
    }

    @Test
    @DisplayName("tanager.ps1 은 UTF-8 BOM 으로 시작한다")
    void ps1StartsWithBom() throws IOException {
        // when
        byte[] bytes = resource("tanager.ps1");

        // then
        assertThat(bytes).hasSizeGreaterThan(3);
        assertThat(bytes[0]).isEqualTo((byte) 0xEF);
        assertThat(bytes[1]).isEqualTo((byte) 0xBB);
        assertThat(bytes[2]).isEqualTo((byte) 0xBF);
    }

    @Test
    @DisplayName("tanager.ps1 의 모든 줄바꿈은 CRLF 다(LF 단독 없음)")
    void ps1UsesCrlfOnly() throws IOException {
        // when
        byte[] bytes = resource("tanager.ps1");

        // then
        int lf = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\n') {
                lf++;
                assertThat(i).as("LF 앞에는 CR").isPositive();
                assertThat(bytes[i - 1]).isEqualTo((byte) '\r');
            }
        }
        assertThat(lf).as("줄 수").isPositive();
    }

    @Test
    @DisplayName("tanager.ps1 의 BOM 뒤는 올바른 UTF-8 이다")
    void ps1IsValidUtf8() throws IOException {
        // given
        byte[] bytes = resource("tanager.ps1");

        // when 잘못된 바이트가 있으면 decode 가 CharacterCodingException 을 던진다
        String text = StandardCharsets.UTF_8
                .newDecoder()
                .decode(ByteBuffer.wrap(bytes, 3, bytes.length - 3))
                .toString();

        // then
        assertThat(text).doesNotContain("�");
    }

    @Test
    @DisplayName("tanager.bash 에는 CR 이 없다")
    void bashHasNoCr() throws IOException {
        // when
        byte[] bytes = resource("tanager.bash");

        // then
        for (byte b : bytes) {
            assertThat(b).isNotEqualTo((byte) 0x0D);
        }
    }

    @Test
    @DisplayName("tanager.bash 에는 BOM 이 없다")
    void bashHasNoBom() throws IOException {
        // when
        byte[] bytes = resource("tanager.bash");

        // then
        assertThat(bytes).isNotEmpty();
        assertThat(bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF)
                .isFalse();
    }

    @Test
    @DisplayName("tanager.bash 는 퍼센트 인코딩에서 local LC_ALL=C 를 쓴다")
    void bashSetsLocalLcAll() throws IOException {
        // when
        String text = new String(resource("tanager.bash"), StandardCharsets.UTF_8);

        // then
        assertThat(text).contains("local LC_ALL=C");
    }
}
