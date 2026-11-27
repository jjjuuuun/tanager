package tanager.application.port.in;

import java.util.Optional;
import tanager.domain.workspace.WindowState;
import tanager.domain.workspace.Workspace;
import tanager.domain.workspace.WorkspaceSnapshot;

/**
 * 시작 때 한 번(FR-RES-02 · 03, #179 · #180). 순수 — 저장된 스냅샷을 이번 실행의 작업 공간 · 창 상태로. 경로가 사라졌는지 · 프로파일이
 * 없는지는 창을 띄울 때 {@link StartSessionUseCase} 가 판정한다(홈에서 열고 알림 · 셸 선택 화면). 저장된 창 자리가 지금 모니터에 보이는지는
 * UI 가 판정한다(모니터 목록은 UI 만 안다).
 */
public interface RestoreWorkspaceUseCase {

    /** {@code snapshot} 이 비면(처음 실행 · 읽을 탭이 없음) 창 p1 하나짜리 {@code Workspace.initial()}. */
    Workspace plan(Optional<WorkspaceSnapshot> snapshot);

    /** {@code snapshot} 이 비면 {@link WindowState#DEFAULT}(OS 가 정한 자리 · 기본 패널). */
    WindowState window(Optional<WorkspaceSnapshot> snapshot);
}
