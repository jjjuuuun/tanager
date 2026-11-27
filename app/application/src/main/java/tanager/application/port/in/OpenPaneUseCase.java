package tanager.application.port.in;

import java.util.Optional;
import java.util.concurrent.CompletionStage;
import tanager.domain.history.WorkspacePath;
import tanager.domain.session.ProfileId;
import tanager.domain.shared.Result;
import tanager.domain.workspace.PaneSpec;

/**
 * 트리의 경로에서 새 창을 연다(FR-HIS-05, #88). 레이아웃과 새 창의 시작 명세만 바꾼다 — 셸은 UI 가 그 창을 그려 크기를 안 뒤
 * {@link StartSessionUseCase} 로 띄운다. 셸 탐지 · 설정 읽기로 블로킹될 수 있어 EDT 에서 부르지 않는다.
 */
public interface OpenPaneUseCase {
    /**
     * 포커스 창 옆(설정 {@code openPlacement})에 {@code path} 에서 여는 창을 만든다. {@code profile} 이 비면 그 환경의 기본 프로파일(기억한
     * 기본이 그 환경이면 그것, 아니면 탐지 순서 첫 셸). 바뀌면 {@code LayoutChanged} 가 한 번 나간다. 응답은 새 창의 명세.
     */
    CompletionStage<Result<PaneSpec, OpenError>> open(WorkspacePath path, Optional<ProfileId> profile);
}
