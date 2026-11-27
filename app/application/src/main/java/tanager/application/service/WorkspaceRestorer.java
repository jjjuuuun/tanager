package tanager.application.service;

import java.util.Objects;
import java.util.Optional;
import tanager.application.port.in.RestoreWorkspaceUseCase;
import tanager.domain.workspace.Workspace;
import tanager.domain.workspace.WorkspaceSnapshot;

/**
 * 저장된 탭 · 분할 · 창별 명세 · 포커스를 그대로 쓴다(FR-RES-02). 다음 창 · 탭 번호는 저장소가 읽을 때 {@code Workspace.restore} 로 이미
 * 계산했다. 창 위치 · 크기 복원은 #180 이다.
 */
public final class WorkspaceRestorer implements RestoreWorkspaceUseCase {

    @Override
    public Workspace plan(Optional<WorkspaceSnapshot> snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return snapshot.map(WorkspaceSnapshot::workspace).orElseGet(Workspace::initial);
    }
}
