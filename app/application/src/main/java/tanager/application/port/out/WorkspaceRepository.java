package tanager.application.port.out;

import tanager.domain.shared.Result;
import tanager.domain.workspace.WorkspaceSnapshot;

/**
 * 작업 공간 · 앱 창 상태를 읽고 쓰는 출력 포트 — state.json 의 workspace · window 부분(ADR-0013 개정, #178). 파일 구현은 {@link
 * HistoryRepository} 와 함께 한 문서를 나눠 쓴다. 블로킹 I/O 라 actor · EDT 에서 부르지 않는다.
 *
 * <p>메서드 이름이 {@code load} · {@code save} 가 아닌 것은 한 클래스가 두 포트를 구현할 때 {@link HistoryRepository#load()} 와
 * 시그니처가 겹치기 때문이다.
 */
public interface WorkspaceRepository {

    /**
     * 작업 공간을 읽는다. 저장한 것이 없으면 스냅샷이 비고 오류가 아니다. 파일 구현은 이력 {@code load} 와 같은 문서 읽기에서 꺼낸다 — 파일 읽기 · 잠금 · 손상 판정
     * · .bak 복구는 문서 단위로 한 번만 하고, 그 알림은 이력 쪽에 온다. 오류 · 읽기 전용 규칙은 {@link HistoryRepository#load()} 와 같다.
     */
    Result<LoadedWorkspace, HistoryStoreError> loadWorkspace();

    /**
     * 원자적으로 쓴다. 성공하면 받은 스냅샷을 돌려준다. 다음 창 · 탭 번호는 저장하지 않는다(읽을 때 {@code Workspace.restore} 가 계산한다).
     *
     * <p>마지막 load(이력 · 작업 공간 어느 쪽이든)가 성공해 {@link StoreAccess#WRITABLE} 로 열었을 때만 쓴다 — {@link
     * HistoryRepository#save} 와 같은 거부 규칙. 파일 구현은 이력 부분에 마지막으로 읽거나 저장한 값을 같이 쓴다.
     */
    Result<WorkspaceSnapshot, HistoryStoreError> saveWorkspace(WorkspaceSnapshot snapshot);
}
