package dev.tanager.application.port.out;

import dev.tanager.domain.history.WorkHistory;
import dev.tanager.domain.shared.Result;

/** 작업 경로 이력을 읽고 쓰는 출력 포트 — state.json 의 history 부분(ADR-0013). 블로킹 I/O 라 actor · EDT 에서 부르지 않는다. */
public interface HistoryRepository {

    /**
     * 이력을 읽는다. 파일이 없으면 빈 이력이고 오류가 아니다. 손상이면 .bak 을 보고, 둘 다 손상이면 원본을 옆으로 옮기고 빈 이력으로 시작한다.
     * 상위 버전이거나 다른 인스턴스가 쓰고 있으면 {@link StoreAccess#READ_ONLY} 로 연다. 읽기 자체가 안 될 때(권한 등)만 오류다.
     */
    Result<LoadedHistory, HistoryStoreError> load();

    /** 원자적으로 쓴다. {@link #load} 가 읽기 전용으로 열었으면 쓰지 않고 {@link HistoryStoreError.ReadOnly}. 성공하면 받은 이력을 돌려준다. */
    Result<WorkHistory, HistoryStoreError> save(WorkHistory history);
}
