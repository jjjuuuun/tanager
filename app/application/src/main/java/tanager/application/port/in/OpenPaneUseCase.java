package tanager.application.port.in;

import java.util.Optional;
import java.util.concurrent.CompletionStage;
import tanager.application.port.out.OpenPlacement;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.session.ProfileId;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.Result;
import tanager.domain.workspace.PaneSpec;

/**
 * 트리의 경로에서 새 창을 연다(FR-HIS-05 · 06, #88 · #91). 레이아웃과 새 창의 시작 명세만 바꾼다 — 셸은 UI 가 그 창을 그려 크기를 안 뒤
 * {@link StartSessionUseCase} 로 띄운다. 셸 탐지 · 설정 읽기로 블로킹될 수 있어 EDT 에서 부르지 않는다.
 */
public interface OpenPaneUseCase {
    /**
     * 포커스 창 옆에 {@code path} 에서 여는 창을 만든다. {@code profile} 이 비면 그 환경의 기본 프로파일(기억한 기본이 그 환경이면 그것, 아니면
     * 탐지 순서 첫 셸). 다른 환경의 프로파일이면 그 환경의 경로로 바꿔 연다(#90). {@code placement} 가 비면 설정 {@code openPlacement}. 바뀌면
     * {@code LayoutChanged} 가 한 번 나간다. 응답은 새 창의 명세.
     */
    CompletionStage<Result<PaneSpec, OpenError>> open(
            WorkspacePath path, Optional<ProfileId> profile, Optional<OpenPlacement> placement);

    /** 환경 {@code env} 의 기본 셸을 그 셸의 시작 경로(→ 홈)에서 연다(환경 노드의 "새 창", #91). */
    CompletionStage<Result<PaneSpec, OpenError>> openHome(ShellEnvironment env, Optional<OpenPlacement> placement);

    /**
     * 세션 노드의 "같은 경로에 새 창"(#92). 창 {@code pane} 옆에 그 세션과 같은 프로파일로, 그 세션이 있는(끝났으면 마지막) 경로에서 연다 —
     * 경로를 모르면 그 프로파일의 시작 경로(→ 홈). {@code placement} 가 비면 설정 {@code openPlacement}. 세션이 없는 창이면
     * {@link OpenError.UnknownPane}.
     */
    CompletionStage<Result<PaneSpec, OpenError>> openSame(PaneId pane, Optional<OpenPlacement> placement);

    /** {@code path} 의 열기 메뉴 후보. 상태를 바꾸지 않는다. */
    OpenChoices choices(WorkspacePath path);
}
