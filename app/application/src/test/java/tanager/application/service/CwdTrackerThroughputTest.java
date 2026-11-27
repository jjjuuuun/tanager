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
 *
 * <p>판정은 절대 처리량이 아니라 같은 실행에서 잰 기준선 대비 비율로 한다(#164). 절대값은 다른 빌드와 겹치면 흔들렸다(601 · 660 · 726 ·
 * 369MB/s). 부하는 기준선과 추적기에 똑같이 걸리므로 비율은 부하와 거의 무관하다. 절대 처리량은 출력에만 남긴다.
 */
@Tag("perf")
class CwdTrackerThroughputTest {
    private static final int MB = 1024 * 1024;
    private static final int TOTAL = 50 * MB;
    private static final int CHUNK = 16 * 1024;
    private static final byte ESC = 0x1B;
    /** 화면 쪽 처리량 추정(넉넉하게 높게 잡은 값). 그 20배 = 추적 비용 5% 이하. */
    private static final double SCREEN_MB_PER_SECOND = 40;

    /**
     * 추적기 처리량 ÷ 기준선 처리량의 하한(#164). 이 PC(WSL, 다른 에이전트 빌드와 겹쳐 load average 9~11, 2026-11-27)에서 다섯 번 잰 값:
     *
     * <ul>
     *   <li>지금 추적기: 0.58 ~ 0.84 (절대값 446 ~ 1,822MB/s 로 네 배 흔들려도 비율은 이 안이었다)
     *   <li>최적화 전 스캐너(#93 이전 {@code OscScanner} — GROUND 에서도 한 바이트씩 상태 기계)를 테스트 쪽에 임시로 옮겨 같은 방법으로 잰
     *       값: 0.095 ~ 0.157
     * </ul>
     *
     * 0.30 은 두 범위의 기하 평균쯤이다 — 지금 값의 최저(0.58)와 옛 값의 최고(0.157) 모두에서 두 배 가까이 떨어져 있다.
     */
    private static final double MIN_RATIO = 0.30;

    private static final int ROUNDS = 5;

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

    /** 걸린 시간과, JIT 가 루프를 없애지 못하게 쓰는 결과(찾은 ESC 수). */
    private record Timing(long nanos, long escapes) {}

    /** 기준선: 같은 조각들에서 ESC 만 찾으며 훑는 가장 단순한 루프 — 스캐너의 GROUND 빠른 길과 같은 일이다. */
    private static Timing baseline(List<byte[]> chunks) {
        long start = System.nanoTime();
        long escapes = 0;
        for (byte[] chunk : chunks) {
            int i = indexOfEsc(chunk, 0);
            while (i >= 0) {
                escapes++;
                i = indexOfEsc(chunk, i + 1);
            }
        }
        return new Timing(System.nanoTime() - start, escapes);
    }

    private static int indexOfEsc(byte[] chunk, int from) {
        for (int i = from; i < chunk.length; i++) {
            if (chunk[i] == ESC) {
                return i;
            }
        }
        return -1;
    }

    private static long trackerNanos(List<byte[]> chunks) {
        CwdTracker tracker = new CwdTracker(
                new PaneId("p1"), new ShellEnvironment.Wsl("Ubuntu"), IntegrationMode.OSC7, new RecordingVisits());
        long start = System.nanoTime();
        for (byte[] chunk : chunks) {
            tracker.accept(chunk);
        }
        return System.nanoTime() - start;
    }

    private static double mbPerSecond(long nanos) {
        return (TOTAL / (double) MB) / (nanos / 1e9);
    }

    @Test
    @DisplayName("50MB 출력을 같은 실행의 ESC 찾기 기준선 대비 일정 비율 이상으로 훑는다 — 한 바이트씩 처리로 퇴행하지 않는다")
    void scansLargeOutputFastRelativeToBaseline() {
        // given
        List<byte[]> chunks = output();
        long escapes = baseline(chunks).escapes(); // JIT 예열
        trackerNanos(chunks);

        // when: 번갈아 다섯 번씩, 각자 가장 빠른 값(다른 테스트 · GC 의 간섭을 줄이고 부하를 둘에 고르게 나눈다)
        long bestBaseline = Long.MAX_VALUE;
        long bestTracker = Long.MAX_VALUE;
        for (int round = 0; round < ROUNDS; round++) {
            Timing timing = baseline(chunks);
            escapes += timing.escapes();
            bestBaseline = Math.min(bestBaseline, timing.nanos());
            bestTracker = Math.min(bestTracker, trackerNanos(chunks));
        }

        // then
        double baselineMbps = mbPerSecond(bestBaseline);
        double trackerMbps = mbPerSecond(bestTracker);
        double ratio = trackerMbps / baselineMbps;
        System.out.printf(
                "CwdTracker 처리량: %.0f MB/s · 기준선 %.0f MB/s · 비율 %.2f (하한 %.2f, 50MB, %d B 조각, 화면 목표 %.0f MB/s)%n",
                trackerMbps, baselineMbps, ratio, MIN_RATIO, CHUNK, SCREEN_MB_PER_SECOND * 20);
        assertThat(escapes).isPositive();
        assertThat(ratio).isGreaterThanOrEqualTo(MIN_RATIO);
    }
}
