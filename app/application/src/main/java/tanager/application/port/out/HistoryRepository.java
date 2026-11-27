package tanager.application.port.out;

import tanager.domain.history.WorkHistory;
import tanager.domain.shared.Result;

/** 작업 경로 이력을 읽고 쓰는 출력 포트 — state.json 의 history 부분(ADR-0013). 블로킹 I/O 라 actor · EDT 에서 부르지 않는다. */
public interface HistoryRepository {

    /**
     * 이력을 읽는다. 파일이 없으면 빈 이력이고 오류가 아니다. 손상이면 .bak 을 보고, 정상이면서 잠금을 쥐었으면 손상 원본을 옆에 복사한 뒤 저장 위치를 그
     * 내용으로 원자적으로 바꾼다 — 어느 단계에서 멈춰도 저장 위치가 비지 않고, 저장 없이 끝나도 다음 load 가 같은 이력을 읽는다. .bak 이
     * 없거나 손상이면 (잠금을 쥐었을 때) 원본을 옆으로 옮기고 빈 이력으로 시작한다. 상위 버전이거나 다른 인스턴스가 쓰고 있으면 {@link StoreAccess#READ_ONLY} 로
     * 연다. 읽기 자체가 안 될 때(권한 등, .bak 포함)와 손상 원본을 남겨 두거나(복사 · 옮기기) 저장 위치를 되돌리지 못할 때만 오류다.
     */
    Result<LoadedHistory, HistoryStoreError> load();

    /**
     * 원자적으로 쓴다. 성공하면 받은 이력을 돌려준다.
     *
     * <p>마지막 {@link #load} 가 성공해 {@link StoreAccess#WRITABLE} 로 열었을 때만 쓴다. 그 밖에는 — load 전, {@link
     * StoreAccess#READ_ONLY} 로 열었을 때, 마지막 load 가 실패했을 때, 구현이 닫을 수 있다면 닫은 뒤 — 파일을 건드리지 않고 {@link
     * HistoryStoreError.ReadOnly} 다. load 때 읽은 다른 키를 모르는 채 빈 이력으로 덮어쓰지 않으려는 것이다(ADR-0013).
     */
    Result<WorkHistory, HistoryStoreError> save(WorkHistory history);
}
