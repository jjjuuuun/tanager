package tanager.ui.measure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.domain.shared.PaneId;

/** 측정 모드 켜기 · 끄기(#222). 꺼져 있으면 프로브를 만들지 않는다 — 비용은 분기 하나다. */
class MeasurementTest {

    @Test
    @DisplayName("꺼져 있으면 팩터리가 no-op 을 주고 창마다 프로브를 만들지 않는다")
    void of_disabled_givesNoOp() {
        // when
        Measurement measurement = Measurement.of(false, () -> 0);

        // then
        assertThat(measurement).isSameAs(Measurement.OFF);
        assertThat(measurement.pane(new PaneId("p1"))).isSameAs(PaneMeter.OFF);
    }

    @Test
    @DisplayName("켜져 있으면 창마다 입력 반응 프로브 하나를 준다 — 같은 창은 같은 프로브")
    void of_enabled_givesProbePerPane() {
        // given
        Measurement measurement = Measurement.of(true, () -> 0);

        // when
        PaneMeter first = measurement.pane(new PaneId("p1"));
        PaneMeter again = measurement.pane(new PaneId("p1"));
        PaneMeter other = measurement.pane(new PaneId("p2"));

        // then
        assertThat(first).isInstanceOf(InputLatencyProbe.class).isSameAs(again).isNotSameAs(other);
    }
}
