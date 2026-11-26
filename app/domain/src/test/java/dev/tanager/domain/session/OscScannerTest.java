package dev.tanager.domain.session;

import static dev.tanager.domain.session.OscFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OscScannerTest {

    private static final CwdReport KOREAN_URI = new CwdReport.FileUri("host", "/mnt/c/한글 #1");

    @Test
    @DisplayName("OSC 7 file URI 를 퍼센트 디코딩해 FileUri 로 보고한다")
    void osc7_percentDecoded() {
        // given
        byte[] bytes = load("osc7-korean-st.bin");
        // when
        List<CwdReport> reports = feedOnce(bytes);
        // then
        assertThat(reports).containsExactly(KOREAN_URI);
    }

    @Test
    @DisplayName("종료자가 BEL 이어도 같은 보고를 낸다")
    void osc7_belTerminator() {
        assertThat(feedOnce(load("osc7-korean-bel.bin"))).containsExactly(KOREAN_URI);
    }

    @Test
    @DisplayName("OSC 9;9 따옴표를 벗겨 WindowsPath 로 보고한다")
    void osc99_quoted() {
        assertThat(feedOnce(load("osc99-quoted.bin")))
                .containsExactly(new CwdReport.WindowsPath("C:\\Users\\user\\한글 폴더"));
    }

    @Test
    @DisplayName("OSC 9;9 따옴표가 없어도(cmd) 그대로 WindowsPath 로 보고한다")
    void osc99_unquoted() {
        assertThat(feedOnce(load("osc99-unquoted.bin"))).containsExactly(new CwdReport.WindowsPath("C:\\work"));
    }

    @Test
    @DisplayName("ConPTY 앞머리 제어 시퀀스와 제목 OSC 는 보고하지 않는다")
    void conptyPreamble_noReports() {
        assertThat(feedOnce(load("conpty-preamble.bin"))).isEmpty();
    }

    @Test
    @DisplayName("조각 하나에 OSC 가 둘이면 순서대로 둘을 보고한다")
    void twoOscsInOneChunk() {
        assertThat(feedOnce(load("two-oscs.bin")))
                .containsExactly(new CwdReport.FileUri("h", "/a"), new CwdReport.WindowsPath("C:\\b"));
    }

    @Test
    @DisplayName("빈 호스트(file:///경로)는 호스트 빈 문자열의 FileUri 다")
    void osc7_emptyHost() {
        assertThat(feedOnce(oscSt("7;file:///tmp"))).containsExactly(new CwdReport.FileUri("", "/tmp"));
    }

    @Test
    @DisplayName("file 스킴이 아니거나 경로가 없는 OSC 7 은 버린다")
    void osc7_notFileUri_dropped() {
        byte[] bytes = concat(oscSt("7;http://h/x"), oscSt("7;file://h"), oscSt("7;/plain"), oscSt("7;file://h/ok"));
        assertThat(feedOnce(bytes)).containsExactly(new CwdReport.FileUri("h", "/ok"));
    }

    @Test
    @DisplayName("7 이나 9;9 로 시작하지 않는 OSC(77 · 9;4 · 8)는 종료자까지 버린다")
    void otherOscNumbers_ignored() {
        byte[] bytes = concat(
                oscSt("77;file://h/a"),
                oscBel("9;4;1;50"),
                oscSt("8;;https://example.com"),
                oscSt("7"),
                oscSt("7;file://h/ok"));
        assertThat(feedOnce(bytes)).containsExactly(new CwdReport.FileUri("h", "/ok"));
    }

    @Test
    @DisplayName("본문이 4096 바이트를 넘는 OSC 7 은 버리고 뒤의 정상 OSC 7 만 보고한다")
    void oversizedBody_dropped() {
        // given: 경로 부분만 4097 바이트
        byte[] huge = oscSt("7;file://h/" + "a".repeat(4097));
        byte[] normal = oscSt("7;file://h/ok");
        byte[] bytes = concat(huge, normal);
        // when / then: 한 번에도, 바이트 단위로도
        assertThat(feedOnce(bytes)).containsExactly(new CwdReport.FileUri("h", "/ok"));
        assertThat(feedAll(OscScanner.initial(), byteByByte(bytes))).containsExactly(new CwdReport.FileUri("h", "/ok"));
    }

    @Test
    @DisplayName("본문이 정확히 4096 바이트면 받고 4097 바이트면 버린다 — BEL · ST, 한 번에 · 바이트 단위 모두")
    void payloadLimitIsExact() {
        // given: 본문 = "7;file://h/" + 경로 나머지
        String head = "7;file://h/";
        String atLimit = head + "a".repeat(OscScanner.MAX_PAYLOAD - head.length());
        String overLimit = atLimit + "a";
        CwdReport expected = new CwdReport.FileUri("h", atLimit.substring("7;file://h".length()));
        for (byte[] ok : List.of(oscSt(atLimit), oscBel(atLimit))) {
            // when / then
            assertThat(feedOnce(ok)).containsExactly(expected);
            assertThat(feedAll(OscScanner.initial(), byteByByte(ok))).containsExactly(expected);
        }
        for (byte[] tooLong : List.of(oscSt(overLimit), oscBel(overLimit))) {
            assertThat(feedOnce(tooLong)).isEmpty();
            assertThat(feedAll(OscScanner.initial(), byteByByte(tooLong))).isEmpty();
        }
    }

    @Test
    @DisplayName("경로에 NUL · 제어 문자(퍼센트 표기 또는 날 바이트)가 있으면 보고를 버리고 뒤의 정상 OSC 로 복귀한다")
    void controlCharactersInPath_dropped() {
        // given
        List<String> bodies = List.of(
                "7;file://h/a%00b",
                "7;file://h/a%0Ab",
                "7;file://h/a%1Bb",
                "7;file://h/a%7Fb",
                "7;file://h%09x/a",
                "7;file://h/a\nb",
                "9;9;C:\\a\tb");
        for (String body : bodies) {
            byte[] bytes = concat(oscSt(body), oscSt("7;file://h/ok"));
            // when / then
            assertThat(feedOnce(bytes)).as(body).containsExactly(new CwdReport.FileUri("h", "/ok"));
        }
    }

    @Test
    @DisplayName("진행 중인 스캐너 하나에 서로 다른 뒷부분을 먹여도 서로 섞이지 않는다 — 본문 버퍼를 공유하지 않는다")
    void pendingScanner_isReusable() {
        // given
        OscScanner pending =
                OscScanner.initial().feed(utf8("\u001b]7;file://h/ab")).next();
        // when
        List<CwdReport> c = pending.feed(utf8("c\u001b\\")).reports();
        List<CwdReport> d = pending.feed(utf8("d\u001b\\")).reports();
        // then
        assertThat(c).containsExactly(new CwdReport.FileUri("h", "/abc"));
        assertThat(d).containsExactly(new CwdReport.FileUri("h", "/abd"));
    }

    @Test
    @DisplayName("4096 바이트 안쪽의 긴 경로는 받아들인다")
    void largeButAllowedBody_accepted() {
        String path = "/" + "a".repeat(4000);
        assertThat(feedOnce(oscSt("7;file://h" + path))).containsExactly(new CwdReport.FileUri("h", path));
    }

    @Test
    @DisplayName("UTF-8 이 아닌 퍼센트 바이트 · 잘못된 퍼센트 표기는 보고 없이 버리고 예외도 없다")
    void invalidEncoding_dropped_thenRecovers() {
        assertThat(feedOnce(load("bad-utf8-then-good.bin"))).containsExactly(new CwdReport.FileUri("h", "/good"));
        assertThat(feedOnce(load("bad-percent-then-good.bin"))).containsExactly(new CwdReport.FileUri("h", "/good"));
    }

    @Test
    @DisplayName("OSC 도중 CAN 이 오면 그 OSC 를 버리고 뒤의 정상 OSC 로 복귀한다")
    void can_cancelsOsc() {
        assertThat(feedOnce(load("can-interrupted.bin"))).containsExactly(new CwdReport.FileUri("h", "/ok"));
    }

    @Test
    @DisplayName("OSC 도중 SUB 도 그 OSC 를 취소한다")
    void sub_cancelsOsc() {
        byte[] bytes = concat(utf8("\u001b]7;file://h/partial\u001a text"), oscSt("7;file://h/ok"));
        assertThat(feedOnce(bytes)).containsExactly(new CwdReport.FileUri("h", "/ok"));
    }

    @Test
    @DisplayName("OSC 안의 ESC 뒤에 '\\' 가 아닌 글자가 오면 그 OSC 는 취소된다")
    void escPlusOther_cancelsOsc() {
        byte[] bytes = concat(utf8("\u001b]7;file://h/partial\u001b[0m rest"), oscSt("7;file://h/ok"));
        assertThat(feedOnce(bytes)).containsExactly(new CwdReport.FileUri("h", "/ok"));
    }

    @Test
    @DisplayName("ST 의 ESC 와 '\\' 가 서로 다른 조각에 나뉘어도 보고한다")
    void stSplitAcrossChunks() {
        byte[] bytes = load("osc7-korean-st.bin");
        List<byte[]> chunks = List.of(
                Arrays.copyOfRange(bytes, 0, bytes.length - 1),
                Arrays.copyOfRange(bytes, bytes.length - 1, bytes.length));
        assertThat(feedAll(OscScanner.initial(), chunks)).containsExactly(KOREAN_URI);
    }

    @Test
    @DisplayName("진행 중인 OSC 가 있으면 다음 스캐너는 그 상태를 이어받는다")
    void pendingOsc_continuesInNextScanner() {
        OscScanner.Scan first = OscScanner.initial().feed(utf8("\u001b]7;file://h/a"));
        assertThat(first.reports()).isEmpty();
        assertThat(first.next()).isNotSameAs(OscScanner.initial());

        OscScanner.Scan second = first.next().feed(utf8("bc\u001b\\"));
        assertThat(second.reports()).containsExactly(new CwdReport.FileUri("h", "/abc"));
    }

    @Test
    @DisplayName("ESC 없는 64KB 조각은 같은 스캐너 인스턴스를 돌려주는 빠른 경로를 탄다")
    void noEsc_fastPath_returnsSameInstance() {
        // given
        byte[] chunk = new byte[64 * 1024];
        Arrays.fill(chunk, (byte) 'x');
        OscScanner initial = OscScanner.initial();
        // when
        OscScanner.Scan scan = initial.feed(chunk);
        // then
        assertThat(scan.next()).isSameAs(initial);
        assertThat(scan.reports()).isEmpty();
    }

    @Test
    @DisplayName("진행 중인 OSC 가 없고 ESC 도 없으면 완료 뒤에도 같은 인스턴스를 돌려준다")
    void afterCompletedOsc_plainText_staysSameInstance() {
        OscScanner idle = OscScanner.initial().feed(oscSt("7;file://h/a")).next();
        assertThat(idle.feed(utf8("plain output")).next()).isSameAs(idle);
    }

    @Test
    @DisplayName("feed 는 받은 배열과 스캐너 자신을 바꾸지 않는다")
    void feed_doesNotMutate() {
        // given
        byte[] chunk = load("osc7-korean-st.bin");
        byte[] copy = chunk.clone();
        OscScanner scanner = OscScanner.initial();
        // when: 같은 스캐너에 같은 조각을 두 번
        List<CwdReport> a = scanner.feed(chunk).reports();
        List<CwdReport> b = scanner.feed(chunk).reports();
        // then
        assertThat(chunk).isEqualTo(copy);
        assertThat(a).isEqualTo(b).containsExactly(KOREAN_URI);
    }

    @Test
    @DisplayName("빈 조각은 아무 일도 하지 않는다")
    void emptyChunk() {
        OscScanner initial = OscScanner.initial();
        OscScanner.Scan scan = initial.feed(new byte[0]);
        assertThat(scan.reports()).isEmpty();
        assertThat(scan.next()).isSameAs(initial);
    }
}
