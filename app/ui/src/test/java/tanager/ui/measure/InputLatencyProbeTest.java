package tanager.ui.measure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.domain.shared.PaneId;

/** 키 → 화면 반영 시간(NFR-01, #222). 시계는 가짜다 — 밀리초 단위로 손으로 진행한다. */
class InputLatencyProbeTest {
    private final AtomicLong nanos = new AtomicLong();
    private final InputLatencyProbe probe = new InputLatencyProbe(new PaneId("p1"), nanos::get);

    @Test
    @DisplayName("키 3개 · 그림 3번(5 · 10 · 40ms)을 모으면 p50 10 · max 40 한 줄이다")
    void report_threeKeys_percentilesLine() {
        // given
        for (long latency : new long[] {5, 10, 40}) {
            probe.keyTyped();
            advance(1);
            probe.output();
            advance(latency - 1);
            probe.painted();
            advance(100);
        }

        // when
        var line = probe.report();

        // then
        assertThat(line).contains("input-latency pane=p1 n=3 p50=10ms p95=40ms p99=40ms max=40ms");
    }

    @Test
    @DisplayName("키 뒤 500ms 안에 그림이 없으면 그 표본은 버린다")
    void report_noPaintWithin500ms_dropsSample() {
        // given
        probe.keyTyped();
        advance(10);
        probe.output();
        advance(491);
        probe.painted();

        // when
        var line = probe.report();

        // then
        assertThat(line).isEmpty();
    }

    @Test
    @DisplayName("출력 없이 그린 것(커서 깜빡임)은 키의 짝이 아니다")
    void painted_withoutOutput_keepsKeyPending() {
        // given
        probe.keyTyped();
        advance(3);
        probe.painted();
        advance(5);
        probe.output();
        advance(4);

        // when
        probe.painted();

        // then
        assertThat(probe.report()).contains("input-latency pane=p1 n=1 p50=12ms p95=12ms p99=12ms max=12ms");
    }

    @Test
    @DisplayName("출력 조각은 그보다 먼저 보낸 키를 모두 답한다 — 출력이 없는 키(빈 줄의 Esc 등)가 뒤 표본을 밀지 않는다")
    void output_answersEveryEarlierKey() {
        // given — 첫 키는 셸이 아무것도 내지 않는다
        probe.keyTyped();
        advance(100);
        probe.keyTyped();
        advance(1);
        probe.output();
        advance(9);
        probe.painted();
        advance(90);
        probe.keyTyped();
        advance(1);
        probe.output();
        advance(9);

        // when
        probe.painted();

        // then — 첫 키만 한 번 길게(110ms) 잡히고 뒤 키들은 제 에코로 잰다
        assertThat(probe.report()).contains("input-latency pane=p1 n=3 p50=10ms p95=110ms p99=110ms max=110ms");
    }

    @Test
    @DisplayName("보고하면 표본을 비운다 — 다음 보고는 그 뒤의 표본만 센다")
    void report_clearsSamples() {
        // given
        probe.keyTyped();
        probe.output();
        advance(7);
        probe.painted();
        probe.report();

        // when
        var next = probe.report();

        // then
        assertThat(next).isEmpty();
    }

    private void advance(long millis) {
        nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
    }
}
