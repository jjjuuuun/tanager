package tanager.application.port.in;

import java.util.function.Consumer;
import tanager.application.port.out.MonitorItem;
import tanager.application.port.out.Subscription;

/**
 * 상태줄의 리소스 표시(FR-MON-01~04, SDD §6.6). 결과는 저장 상태가 아니라 StateActor 를 거치지 않는다. 어느 메서드도 막지 않는다.
 */
public interface WatchResourcesUseCase {

    /**
     * 주기마다 표본을 받는다. {@code listener} 는 UI 스레드(EDT)에서 불린다. 구독이 하나도 없거나 {@code monitor.enabled=false} 이거나
     * 모든 항목을 껐으면 수집하지 않는다. 첫 구독 때 바로 한 번 잰다.
     */
    Subscription subscribe(Consumer<ResourceReport> listener);

    /** 상태줄에서 항목을 끄고 켠다. 끈 항목은 수집도 멈춘다. 실행 중에만 유효하다 — 저장은 설정 파일({@code monitor.items})이 한다. */
    void setItemVisible(MonitorItem item, boolean visible);

    /** 항목을 누르면 OS 도구를 연다(작업 관리자 · 디스크 정리). 백그라운드에서 띄운다. */
    void openTool(MonitorItem item);
}
