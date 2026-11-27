package tanager.application.port.in;

import java.util.Optional;
import java.util.concurrent.CompletionStage;
import tanager.domain.shared.Result;
import tanager.domain.shared.TabId;
import tanager.domain.workspace.TabError;
import tanager.domain.workspace.Workspace;

/**
 * 탭 만들기 · 닫기 · 이름 · 순서 · 전환(FR-LAY-07, #177). 작업 공간이 바뀌면 {@code LayoutChanged} 가 한 번 나가고, 실패 · 그대로면 이벤트가
 * 없다. 설정을 읽지 않고 상태 실행기에 넣기만 해서 EDT 에서 불러도 막히지 않는다.
 */
public interface ManageTabUseCase {
    /** 맨 뒤에 새 탭을 만들어 활성화한다. 새 창은 기억한 기본 셸을 그 셸의 시작 경로(→ 홈)에서 띄운다. */
    CompletionStage<Result<Workspace, TabError>> create();

    /**
     * 탭을 닫는다. 그 탭 창들의 세션을 트리에서 빼고 {@code LayoutChanged} 다음 {@code SessionsChanged} 를 낸다. 마지막 탭이면 작업 공간은 그대로
     * 두고 {@code LastPaneClosed} 를 낸다(응답 {@link TabError.LastTab}) — 마지막 창 닫기와 같다. 실행 중 셸이 여럿일 때의 확인은 UI 몫이다.
     */
    CompletionStage<Result<Workspace, TabError>> close(TabId tab);

    /** 탭 이름을 바꾼다. 비면 자동 이름(포커스 창 제목). 쓸 수 없는 이름이면 {@link TabError.InvalidName}. */
    CompletionStage<Result<Workspace, TabError>> rename(TabId tab, Optional<String> name);

    /** 탭을 {@code toIndex}(0 부터) 자리로 옮긴다. 범위 밖은 양 끝으로 고정한다. */
    CompletionStage<Result<Workspace, TabError>> move(TabId tab, int toIndex);

    /** 탭을 활성화한다. */
    CompletionStage<Result<Workspace, TabError>> select(TabId tab);

    /** 활성 탭에서 {@code delta} 만큼 옆 탭을 활성화한다(Ctrl+Tab · Ctrl+Shift+Tab). 양 끝에서 돈다. */
    CompletionStage<Result<Workspace, TabError>> selectRelative(int delta);

    /** {@code index}(0 부터) 자리의 탭을 활성화한다(Ctrl+1…9). 없는 번호면 {@link TabError.NoSuchIndex}. */
    CompletionStage<Result<Workspace, TabError>> selectIndex(int index);
}
