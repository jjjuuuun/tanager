package tanager.domain.session;

import static org.assertj.core.api.Assertions.assertThat;
import static tanager.domain.session.OscFixtures.*;

import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class OscScannerProperties {

    @Property
    @Label("고정 시퀀스를 임의 위치에서 나눠 먹여도 한 번에 먹인 것과 같은 보고를 낸다")
    void fixtureSplitInvariant(@ForAll("fixtureNames") String name, @ForAll("cuts") List<Integer> cuts) {
        byte[] bytes = load(name);
        assertThat(feedAll(OscScanner.initial(), splitAt(bytes, cuts))).isEqualTo(feedOnce(bytes));
    }

    @Property
    @Label("고정 시퀀스를 바이트 하나씩 먹여도 한 번에 먹인 것과 같은 보고를 낸다")
    void fixtureByteByByte(@ForAll("fixtureNames") String name) {
        byte[] bytes = load(name);
        assertThat(feedAll(OscScanner.initial(), byteByByte(bytes))).isEqualTo(feedOnce(bytes));
    }

    @Property
    @Label("제어 문자가 섞인 임의 바이트열은 어떻게 나눠도 같은 보고를 내고 예외가 없다")
    void arbitraryBytesSplitInvariant(@ForAll("noisyStreams") byte[] bytes, @ForAll("cuts") List<Integer> cuts) {
        List<CwdReport> whole = feedOnce(bytes);
        assertThat(feedAll(OscScanner.initial(), splitAt(bytes, cuts))).isEqualTo(whole);
        assertThat(feedAll(OscScanner.initial(), byteByByte(bytes))).isEqualTo(whole);
    }

    @Property
    @Label("만들어 낸 OSC 7 경로는 종료자 · 나누는 위치와 상관없이 그대로 복원된다")
    void generatedOsc7RoundTrip(
            @ForAll("segments") List<String> segs, @ForAll boolean bel, @ForAll("cuts") List<Integer> cuts) {
        String path = "/" + String.join("/", segs);
        byte[] seq = bel ? oscBel("7;file://h" + percentEncode(path)) : oscSt("7;file://h" + percentEncode(path));
        byte[] bytes = concat(utf8("noise"), seq, utf8("tail"));

        assertThat(feedAll(OscScanner.initial(), splitAt(bytes, cuts)))
                .containsExactly(new CwdReport.FileUri("h", path));
    }

    @Provide
    Arbitrary<String> fixtureNames() {
        return Arbitraries.of(
                "osc7-korean-st.bin",
                "osc7-korean-bel.bin",
                "osc99-quoted.bin",
                "osc99-unquoted.bin",
                "conpty-preamble.bin",
                "two-oscs.bin",
                "can-interrupted.bin",
                "bad-utf8-then-good.bin",
                "bad-percent-then-good.bin");
    }

    @Provide
    Arbitrary<List<Integer>> cuts() {
        return Arbitraries.integers().between(0, 300).list().ofMaxSize(12);
    }

    @Provide
    Arbitrary<byte[]> noisyStreams() {
        Arbitrary<byte[]> piece = Arbitraries.oneOf(
                Arbitraries.of(
                        utf8("\u001b]7;file://h/"),
                        utf8("\u001b]9;9;"),
                        utf8("\u001b]0;"),
                        utf8("\u001b\\"),
                        utf8("\u001b["),
                        new byte[] {BEL},
                        new byte[] {0x18},
                        new byte[] {0x1A},
                        utf8("%41"),
                        utf8("%ZZ"),
                        utf8("\""),
                        utf8("/a/b"),
                        utf8("C:\\x")),
                Arbitraries.bytes().array(byte[].class).ofMaxSize(6));
        return piece.list().ofMaxSize(30).map(parts -> concat(parts.toArray(new byte[0][])));
    }

    @Provide
    Arbitrary<List<String>> segments() {
        Arbitrary<String> seg = Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars('가', '힣', ' ', '#', '%', '?')
                .ofMinLength(1)
                .ofMaxLength(8)
                .filter(s -> !s.equals(".") && !s.equals(".."));
        return seg.list().ofMinSize(1).ofMaxSize(5);
    }

    /** 테스트용 퍼센트 인코딩: 영숫자 · '/' 외는 UTF-8 바이트마다 %XX. */
    private static String percentEncode(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : utf8(s)) {
            char c = (char) (b & 0xFF);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '/') {
                sb.append(c);
            } else {
                sb.append(String.format("%%%02X", b & 0xFF));
            }
        }
        return sb.toString();
    }
}
