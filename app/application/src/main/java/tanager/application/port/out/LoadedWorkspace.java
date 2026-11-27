package tanager.application.port.out;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tanager.domain.workspace.WorkspaceSnapshot;

/**
 * 읽은 작업 공간과 저장소를 연 방식, 읽는 동안 생긴 일(#178).
 *
 * @param snapshot 복원할 작업 공간 · 창 상태. 저장한 것이 없거나 읽을 수 있는 탭이 남지 않으면 비어 있다
 * @param access 이후 저장할 수 있는지(이력과 같은 문서라 같은 값)
 * @param notices 부분 손상 · 마이그레이션 등 사용자에게 알릴 일. 문서 단위의 일(복구 · 상위 버전 · 잠금)은 이력 쪽 알림에 있다
 */
public record LoadedWorkspace(
        Optional<WorkspaceSnapshot> snapshot, StoreAccess access, List<WorkspaceLoadNotice> notices) {
    public LoadedWorkspace {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(access, "access");
        notices = List.copyOf(notices);
    }
}
