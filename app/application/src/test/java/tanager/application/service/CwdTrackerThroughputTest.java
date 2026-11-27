package tanager.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tanager.application.port.in.RecordingVisits;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.session.IntegrationMode;
import tanager.domain.shared.PaneId;

/**
 * 큰 출력(50MB) 중 cwd 추적의 비용(#93, NFR-01, ADR-0003 미확인 항목). 셸 출력은 PTY 읽기 스레드에서 터미널 화면과 추적기가 함께
 * 받는다 — 추적기가 화면(ConPTY + JediTerm)보다 20배 이상 빠르면 추적이 더하는 비용은 5% 안이다. 화면 쪽 처리량은 추정값이다 — 실제
 * 파이프라인(셸 → ConPTY → 화면)에서 켬 · 끔을 비교하는 일은 릴리스 수동 확인 항목으로 남긴다.
 */
@Tag("perf")
class CwdTrackerThroughputTest {
    private static final int MB = 1024 * 1024;
    private static final int TOTAL = 50 * MB;
    private static final int CHUNK = 16 * 1024;
    /** 화면 쪽 처리량 추정(넉넉하게 높게 잡은 값). 그 20배 = 추적 비용 5% 이하. */
    private static final double SCREEN_MB_PER_SECOND = 40;

    /**
     * 실패 기준. 최적화 뒤 평소 2,000MB/s 넘게 나오고, 최적화 전(GROUND 에서 한 바이트씩)은 230MB/s 였다(#93). 400 은 그 퇴행을 잡고, 다른
     * 빌드와 겹친 부하(660MB/s 측정, #145)에서도 흔들리지 않는 값이다. 목표(화면의 20배 = 800)는 아래 출력과 ADR-0003 에 남긴다.
     */
    private static final double REQUIRED_MB_PER_SECOND = 400;

    private static final int RUNS = 5;

    /** 색 · 커서 이동이 섞인 출력. 1MB 마다 OSC 7 보고가 한 번 들어 있다. */
    private static List<byte[]> output() {
        String line = "\u001b[32m-rw-r--r--\u001b[0m 1 me me  4096 Nov 27 10:00 \u001b[1;34m파일-이름.txt\u001b[0m\r\n";
        byte[] lineBytes = line.getBytes(StandardCharsets.UTF_8);
        byte[] report = "\u001b]7;file://PC/home/me/work\u0007".getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[TOTAL];
        int at = 0;
        while (at < TOTAL) {
            byte[] next = at % MB < lineBytes.length ? report : lineBytes;
            int n = Math.min(next.length, TOTAL - at);
            System.arraycopy(next, 0, all, at, n);
            at += n;
        }
        List<byte[]> chunks = new ArrayList<>();
        for (int offset = 0; offset < TOTAL; offset += CHUNK) {
            byte[] chunk = new byte[Math.min(CHUNK, TOTAL - offset)];
            System.arraycopy(all, offset, chunk, 0, chunk.length);
            chunks.add(chunk);
        }
        return chunks;
    }

    private static long feedNanos(List<byte[]> chunks) {
        CwdTracker tracker = new CwdTracker(
                new PaneId("p1"), new ShellEnvironment.Wsl("Ubuntu"), IntegrationMode.OSC7, new RecordingVisits());
        long start = System.nanoTime();
        for (byte[] chunk : chunks) {
            tracker.accept(chunk);
        }
        return System.nanoTime() - start;
    }

    @Test
    @DisplayName("50MB 출력을 400MB/s 이상으로 훑는다 — 최적화 전(230MB/s)으로 퇴행하지 않는다")
    void scansLargeOutputFastEnough() {
        // given
        List<byte[]> chunks = output();
        feedNanos(chunks); // JIT 예열

        // when: 다섯 번 중 가장 빠른 값(다른 테스트 · GC 의 간섭을 줄인다)
        long best = Long.MAX_VALUE;
        for (int i = 0; i < RUNS; i++) {
            best = Math.min(best, feedNanos(chunks));
        }

        // then
        double mbPerSecond = (TOTAL / (double) MB) / (best / 1e9);
        System.out.printf(
                "CwdTracker 처리량: %.0f MB/s (50MB, %d B 조각, 목표 %.0f)%n", mbPerSecond, CHUNK, SCREEN_MB_PER_SECOND * 20);
        assertThat(mbPerSecond).isGreaterThanOrEqualTo(REQUIRED_MB_PER_SECOND);
    }
}
