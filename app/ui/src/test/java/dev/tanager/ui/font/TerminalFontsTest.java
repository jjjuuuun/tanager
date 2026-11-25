package dev.tanager.ui.font;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tanager.domain.shared.Result;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TerminalFontsTest {
    private static byte[] resource(String name) throws IOException {
        try (InputStream in = TerminalFontsTest.class.getResourceAsStream("/fonts/" + name)) {
            assertThat(in).as("/fonts/" + name).isNotNull();
            return in.readAllBytes();
        }
    }

    @Test
    @DisplayName("번들된 ttf 두 개의 SHA-256 이 SHA256SUMS 와 같고 OFL.txt 가 함께 있다")
    void bundledFontsMatchChecksums() throws Exception {
        // given
        Map<String, String> sums = new TreeMap<>();
        for (String line : new String(resource("SHA256SUMS"), StandardCharsets.UTF_8).split("\\R")) {
            String[] parts = line.trim().split("\\s+", 2);
            if (parts.length == 2) {
                sums.put(parts[1].replaceFirst("^\\*", ""), parts[0].toLowerCase());
            }
        }

        // when / then
        assertThat(sums).hasSize(2);
        assertThat(sums.keySet()).allMatch(n -> n.endsWith(".ttf"));
        for (Map.Entry<String, String> e : sums.entrySet()) {
            String actual = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(resource(e.getKey())));
            assertThat(actual).as(e.getKey()).isEqualTo(e.getValue());
        }
        assertThat(resource("OFL.txt")).isNotEmpty();
    }

    @Test
    @DisplayName("번들 글꼴을 등록하면 D2Coding 이고 Bold 도 있으며 한글을 그릴 수 있다")
    void registersBundledFont() {
        // when
        Result<java.awt.Font, String> result = TerminalFonts.registerBundled();

        // then
        assertThat(result).isInstanceOf(Result.Ok.class);
        java.awt.Font font = ((Result.Ok<java.awt.Font, String>) result).get();
        assertThat(font.getFamily()).isEqualTo(TerminalFonts.FAMILY);
        assertThat(java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames())
                .contains("D2Coding");
        assertThat(new java.awt.Font(TerminalFonts.FAMILY, java.awt.Font.BOLD, 14).isBold())
                .isTrue();
        assertThat(TerminalFonts.supportsHangul(font)).isTrue();
    }

    @Test
    @DisplayName("라틴 문자만 그리는 글꼴은 한글 지원이 아니다")
    void latinOnlyIsNotHangul() {
        assertThat(TerminalFonts.supportsHangul(cp -> cp < 0x250)).isFalse();
    }

    @Test
    @DisplayName("음절만 있고 자모가 없는 글꼴은 한글 지원이 아니다")
    void syllablesWithoutJamoIsNotHangul() {
        // 한글 음절 블록(AC00..D7A3)만 true
        assertThat(TerminalFonts.supportsHangul(cp -> cp >= 0xAC00 && cp <= 0xD7A3))
                .isFalse();
    }

    @Test
    @DisplayName("샘플 글자를 모두 그리면 한글 지원이다")
    void everythingSupportedIsHangul() {
        assertThat(TerminalFonts.supportsHangul(cp -> true)).isTrue();
    }
}
