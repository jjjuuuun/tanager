package tanager.application.port.in;

import java.util.Optional;
import tanager.domain.workspace.Workspace;
import tanager.domain.workspace.WorkspaceSnapshot;

/**
 * 시작 때 한 번(FR-RES-02, #179). 순수 — 저장된 스냅샷을 이번 실행의 작업 공간으로. 경로가 사라졌는지 · 프로파일이 없는지는 창을 띄울 때
 * {@link StartSessionUseCase} 가 판정한다(홈에서 열고 알림 · 셸 선택 화면).
 */
public interface RestoreWorkspaceUseCase {

    /** {@code snapshot} 이 비면(처음 실행 · 읽을 탭이 없음) 창 p1 하나짜리 {@code Workspace.initial()}. */
    Workspace plan(Optional<WorkspaceSnapshot> snapshot);
}
