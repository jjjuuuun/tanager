package dev.tanager.ui.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class Utf8ChunkDecoderTest {
    private static final byte[] HAN = "한".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("멀티바이트 문자가 두 조각에 걸치면 다음 조각이 올 때까지 들고 있다가 한 글자로 낸다")
    void multibyteAcrossChunks() {
        // given
        Utf8ChunkDecoder decoder = new Utf8ChunkDecoder();

        // when
        String first = decoder.decode(new byte[] {HAN[0]});
        String second = decoder.decode(new byte[] {HAN[1], HAN[2]});

        // then
        assertThat(first).isEmpty();
        assertThat(second).isEqualTo("한");
    }

    @Test
    @DisplayName("끝에 남은 불완전한 바이트는 finish 에서 U+FFFD 한 글자가 된다")
    void leftoverBecomesReplacementOnFinish() {
        // given
        Utf8ChunkDecoder decoder = new Utf8ChunkDecoder();
        decoder.decode(new byte[] {HAN[0]});

        // when / then
        assertThat(decoder.finish()).isEqualTo("�");
    }

    @Test
    @DisplayName("잘못된 바이트는 U+FFFD 로 바꾸고 뒤따르는 글자는 살린다")
    void invalidByteBecomesReplacement() {
        // given
        Utf8ChunkDecoder decoder = new Utf8ChunkDecoder();

        // when
        String out = decoder.decode(new byte[] {(byte) 0xFF, 'a'});

        // then
        assertThat(out).isEqualTo("�a");
    }

    @Property
    @Label("임의의 문자열을 임의의 지점에서 둘로 쪼개 디코딩해도 한 번에 디코딩한 것과 같다")
    void splitAnywhereDecodesSame(@ForAll("texts") String text, @ForAll("fractions") double at) {
        // given
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int cut = (int) Math.floor(at * bytes.length);
        Utf8ChunkDecoder decoder = new Utf8ChunkDecoder();

        // when
        String joined = decoder.decode(java.util.Arrays.copyOfRange(bytes, 0, cut))
                + decoder.decode(java.util.Arrays.copyOfRange(bytes, cut, bytes.length))
                + decoder.finish();

        // then
        assertThat(joined).isEqualTo(text);
    }

    @Provide
    Arbitrary<String> texts() {
        // 서로게이트 쌍이 깨지지 않게 BMP 의 한글·라틴·기호만
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('가', '힣')
                .withCharRange('─', '╿')
                .ofMaxLength(40);
    }

    @Provide
    Arbitrary<Double> fractions() {
        return Arbitraries.doubles().between(0.0, 1.0);
    }
}
