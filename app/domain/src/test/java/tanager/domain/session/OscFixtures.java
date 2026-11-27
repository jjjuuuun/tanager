package tanager.domain.session;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** OSC 스캐너 테스트용 바이트 도우미. 픽스처는 src/test/resources/osc/*.bin (셸이 낸 바이트 그대로). */
final class OscFixtures {
    static final byte ESC = 0x1B;
    static final byte BEL = 0x07;

    private OscFixtures() {}

    static byte[] load(String name) {
        try (InputStream in = OscFixtures.class.getResourceAsStream("/osc/" + name)) {
            if (in == null) {
                throw new IllegalStateException("픽스처 없음: /osc/" + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** ESC ] body ST(ESC \). */
    static byte[] oscSt(String body) {
        return concat(new byte[] {ESC, ']'}, utf8(body), new byte[] {ESC, '\\'});
    }

    /** ESC ] body BEL. */
    static byte[] oscBel(String body) {
        return concat(new byte[] {ESC, ']'}, utf8(body), new byte[] {BEL});
    }

    static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }

    /** 조각을 차례로 먹여 모든 보고를 모은다. */
    static List<CwdReport> feedAll(OscScanner start, List<byte[]> chunks) {
        OscScanner scanner = start;
        List<CwdReport> all = new ArrayList<>();
        for (byte[] chunk : chunks) {
            OscScanner.Scan scan = scanner.feed(chunk);
            all.addAll(scan.reports());
            scanner = scan.next();
        }
        return all;
    }

    static List<CwdReport> feedOnce(byte[] bytes) {
        return feedAll(OscScanner.initial(), List.of(bytes));
    }

    /** 바이트 하나씩. */
    static List<byte[]> byteByByte(byte[] bytes) {
        List<byte[]> chunks = new ArrayList<>();
        for (byte b : bytes) {
            chunks.add(new byte[] {b});
        }
        return chunks;
    }

    /** cuts(오름차순 아닐 수 있음 · 범위 밖 허용)에서 잘라 조각 목록으로. 빈 조각도 생길 수 있다. */
    static List<byte[]> splitAt(byte[] bytes, List<Integer> cuts) {
        List<Integer> points = new ArrayList<>();
        for (int c : cuts) {
            points.add(Math.floorMod(c, bytes.length + 1));
        }
        points.sort(Integer::compare);
        List<byte[]> chunks = new ArrayList<>();
        int prev = 0;
        for (int p : points) {
            chunks.add(java.util.Arrays.copyOfRange(bytes, prev, p));
            prev = p;
        }
        chunks.add(java.util.Arrays.copyOfRange(bytes, prev, bytes.length));
        return chunks;
    }
}
