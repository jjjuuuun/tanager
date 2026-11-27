package tanager.ui.status;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.out.MonitorItem;

/** 좁은 상태줄에서 숨길 항목(순수, #188). WSL → 디스크 → 메모리 순으로 숨기고 CPU 는 남긴다. */
class StatusFitTest {
    // 실제 상태줄과 비슷한 폭(px, 간격 포함)
    private static final List<StatusFit.Width> ITEMS = List.of(
            new StatusFit.Width(MonitorItem.CPU, 70),
            new StatusFit.Width(MonitorItem.MEMORY, 170),
            new StatusFit.Width(MonitorItem.DISK, 150),
            new StatusFit.Width(MonitorItem.WSL, 140),
            new StatusFit.Width(MonitorItem.WSL, 130));

    @Test
    @DisplayName("폭이 넉넉하면 모두 보인다")
    void allFit() {
        // when
        var visible = StatusFit.visible(ITEMS, 660);

        // then
        assertThat(visible).containsExactly(MonitorItem.CPU, MonitorItem.MEMORY, MonitorItem.DISK, MonitorItem.WSL);
    }

    @Test
    @DisplayName("모자라면 WSL 두 항목을 한꺼번에 먼저 숨긴다")
    void wslFirst() {
        // when
        var visible = StatusFit.visible(ITEMS, 659);

        // then
        assertThat(visible).containsExactly(MonitorItem.CPU, MonitorItem.MEMORY, MonitorItem.DISK);
    }

    @Test
    @DisplayName("폭 700px 창(알림 · 왼쪽 몫을 뺀 300px)이면 WSL · 디스크를 숨기고 CPU · 메모리가 남는다")
    void narrowWindow() {
        // given: 700 - 좌우 여백 20 - 왼쪽 경로 몫 200 - 알림 180
        int available = 700 - 20 - 200 - 180;

        // when
        var visible = StatusFit.visible(ITEMS, available);

        // then
        assertThat(visible).containsExactly(MonitorItem.CPU, MonitorItem.MEMORY);
    }

    @Test
    @DisplayName("그래도 모자라면 메모리까지 숨기고, 폭이 0 이어도 CPU 는 남는다")
    void cpuStays() {
        assertThat(StatusFit.visible(ITEMS, 239)).containsExactly(MonitorItem.CPU);
        assertThat(StatusFit.visible(ITEMS, 0)).containsExactly(MonitorItem.CPU);
    }

    @Test
    @DisplayName("없는 항목은 결과에도 없다")
    void onlyGivenItems() {
        // when
        var visible = StatusFit.visible(
                List.of(new StatusFit.Width(MonitorItem.MEMORY, 100), new StatusFit.Width(MonitorItem.DISK, 100)), 150);

        // then
        assertThat(visible).containsExactly(MonitorItem.MEMORY);
    }
}
