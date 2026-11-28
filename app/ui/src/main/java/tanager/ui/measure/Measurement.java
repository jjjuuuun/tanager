package tanager.ui.measure;

import java.util.function.LongSupplier;
import tanager.domain.shared.PaneId;

/**
 * 측정 모드(#222 — NFR-01 입력 반응 · NFR-10 EDT 차단 · NFR-11 전환 효과와 입력). 실행 옵션 {@code -Dtanager.measure=true} 일 때만
 * 켜진다(조립 루트가 읽는다). 꺼져 있으면 {@link #OFF} 하나뿐이라 프로브 · 타이머 · 스레드를 만들지 않는다. 결과는 로그 한 줄씩이고 화면
 * 글자를 만들지 않는다.
 */
public sealed interface Measurement permits Measurement.Off, ActiveMeasurement {
    /** 아무것도 재지 않는다. */
    Measurement OFF = Off.INSTANCE;

    /** {@code enabled} 이면 시스템 시계({@link System#nanoTime()})로 재는 측정, 아니면 {@link #OFF}. */
    static Measurement of(boolean enabled) {
        return of(enabled, System::nanoTime);
    }

    /** 시계를 받는다(테스트). */
    static Measurement of(boolean enabled, LongSupplier nanos) {
        return enabled ? new ActiveMeasurement(nanos) : OFF;
    }

    /** 창 {@code pane} 의 입력 반응 고리. 같은 창이면 같은 고리다(셸을 다시 띄워도 이어 잰다). */
    PaneMeter pane(PaneId pane);

    /** EDT 정지 감시와 창별 입력 반응 보고(30초마다)를 시작한다. EDT 에서 한 번 부른다. */
    void start();

    /** 측정 모드가 꺼져 있을 때. */
    enum Off implements Measurement {
        INSTANCE;

        @Override
        public PaneMeter pane(PaneId pane) {
            return PaneMeter.OFF;
        }

        @Override
        public void start() {}
    }
}
