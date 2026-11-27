package tanager.ui.font;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.domain.shared.Result;

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
        Result<TerminalFontSet, String> result = TerminalFonts.registerBundled();

        // then
        assertThat(result).isInstanceOf(Result.Ok.class);
        java.awt.Font font = ((Result.Ok<TerminalFontSet, String>) result).get().regular();
        assertThat(font.getFamily()).isEqualTo(TerminalFonts.FAMILY);
        assertThat(java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames())
                .contains("D2Coding");
        assertThat(new java.awt.Font(TerminalFonts.FAMILY, java.awt.Font.BOLD, 14).isBold())
                .isTrue();
        assertThat(TerminalFonts.supportsHangul(font)).isTrue();
    }

    @Test
    @DisplayName("등록 뒤 GraphicsEnvironment 에 D2Coding Bold 글꼴이 있다(Font.isBold 는 스타일 플래그라 증거가 아니다)")
    void registersBoldFace() {
        // when
        TerminalFonts.registerBundled();

        // then
        assertThat(java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getAllFonts())
                .extracting(f -> f.getFontName(java.util.Locale.ROOT))
                .contains("D2Coding", "D2Coding Bold");
    }

    @Test
    @DisplayName("리소스가 없으면 Err")
    void missingResourceIsErr() {
        Result<TerminalFontSet, String> result =
                TerminalFonts.register(java.util.List.of("/fonts/nope.ttf"), TerminalFonts.class::getResourceAsStream);

        assertThat(result.error()).hasValueSatisfying(e -> assertThat(e).contains("/fonts/nope.ttf"));
    }

    @Test
    @DisplayName("글꼴이 아닌 바이트면 Err")
    void notAFontIsErr() {
        Result<TerminalFontSet, String> result =
                TerminalFonts.register(java.util.List.of("/fonts/OFL.txt"), TerminalFonts.class::getResourceAsStream);

        assertThat(result.isOk()).isFalse();
    }

    @Test
    @DisplayName("열다가 RuntimeException 이 나도 예외 대신 Err(창은 떠야 한다)")
    void runtimeExceptionIsErr() {
        Result<TerminalFontSet, String> result = TerminalFonts.register(java.util.List.of("/fonts/x.ttf"), name -> {
            throw new IllegalStateException("주입된 실패");
        });

        assertThat(result.error()).hasValueSatisfying(e -> assertThat(e).contains("주입된 실패"));
    }

    @Test
    @DisplayName("VirtualMachineError 가 아닌 Error(AWTError 등)도 Err")
    void nonVmErrorIsErr() {
        Result<TerminalFontSet, String> result = TerminalFonts.register(java.util.List.of("/fonts/x.ttf"), name -> {
            throw new java.awt.AWTError("주입된 AWT 오류");
        });

        assertThat(result.isOk()).isFalse();
    }

    @Test
    @DisplayName("글꼴 열기 함수가 InternalError 를 던져도 Err(글꼴 하위 시스템 오류로 창이 안 뜨면 안 된다)")
    void internalErrorIsErr() {
        Result<TerminalFontSet, String> result = TerminalFonts.register(java.util.List.of("/fonts/x.ttf"), name -> {
            throw new InternalError("주입된 글꼴 오류");
        });

        assertThat(result.error()).hasValueSatisfying(e -> assertThat(e).contains("주입된 글꼴 오류"));
    }

    @Test
    @DisplayName("sized 는 Regular · Bold 를 같은 크기로 만든다")
    void sizedAppliesToBothFaces() {
        TerminalFontSet sized = TerminalFontSet.fallback().sized(14f);

        assertThat(sized.regular().getSize2D()).isEqualTo(14f);
        assertThat(sized.bold().getSize2D()).isEqualTo(14f);
    }

    @Test
    @DisplayName("VirtualMachineError(메모리 부족 등)는 삼키지 않는다")
    void virtualMachineErrorPropagates() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> TerminalFonts.register(java.util.List.of("/fonts/x.ttf"), name -> {
                            throw new OutOfMemoryError("주입");
                        }))
                .isInstanceOf(OutOfMemoryError.class);
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
