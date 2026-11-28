package tanager.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 리소스 수집 자체의 부하(NFR-10, #222). 시각 · CPU 시간은 가짜 값이다. */
class CollectionLoadTest {
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);
    private static final long MILLI = TimeUnit.MILLISECONDS.toNanos(1);

    @Test
    @DisplayName("60초 동안 수집 스레드 CPU 120ms 면 한 코어의 0.20% · 8코어 전체의 0.03% 다")
    void line_cpuShareOfWindow() {
        // given
        CollectionLoad load = CollectionLoad.startingAt(0);
        for (int i = 0; i < 30; i++) {
            load = load.collected(4 * MILLI);
        }

        // when
        String line = load.line(60 * SECOND, 8);

        // then
        assertThat(line)
                .isEqualTo("resource-load window=60s tasks=30 cpuCore=0.20% cpuAll=0.03% wslCalls=0 wslMinGap=-");
    }

    @Test
    @DisplayName("wsl.exe 호출 간격의 최솟값을 적는다")
    void line_minimumWslGap() {
        // given
        CollectionLoad load = CollectionLoad.startingAt(0)
                .wslCalled(1 * SECOND)
                .wslCalled(6 * SECOND + 3 * MILLI)
                .wslCalled(11 * SECOND + 4 * MILLI)
                .wslCalled(20 * SECOND);

        // when
        String line = load.line(60 * SECOND, 4);

        // then
        assertThat(line).endsWith("wslCalls=4 wslMinGap=5001ms");
    }

    @Test
    @DisplayName("다음 창은 앞 창의 마지막 호출부터 간격을 잰다 — 창 경계를 넘는 짧은 간격도 놓치지 않는다")
    void next_keepsLastWslCall() {
        // given
        CollectionLoad load =
                CollectionLoad.startingAt(0).wslCalled(59 * SECOND).collected(5 * MILLI);

        // when
        CollectionLoad next = load.next(60 * SECOND).wslCalled(61 * SECOND);

        // then
        assertThat(next.line(120 * SECOND, 4))
                .isEqualTo("resource-load window=60s tasks=0 cpuCore=0.00% cpuAll=0.00% wslCalls=1 wslMinGap=2000ms");
    }

    @Test
    @DisplayName("CPU 시간을 잴 수 없으면(스레드 CPU 시간 미지원) 그렇다고 적는다")
    void line_cpuUnavailable() {
        // given
        CollectionLoad load = CollectionLoad.startingAt(0).collectedWithoutCpu();

        // when
        String line = load.line(60 * SECOND, 4);

        // then
        assertThat(line)
                .isEqualTo(
                        "resource-load window=60s tasks=1 cpuCore=unavailable cpuAll=unavailable wslCalls=0 wslMinGap=-");
    }
}
