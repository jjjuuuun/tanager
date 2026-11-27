package tanager.application.port.in;

import tanager.domain.history.HistoryTree;

/**
 * 사이드바가 그릴 이력 트리(FR-HIS-02 · 06). 확정된 상태 위의 순수 투영이라 어느 스레드(EDT 포함)에서나 바로 부른다 — 액터를 기다리지
 * 않는다.
 */
public interface QueryHistoryTreeUseCase {

    /**
     * @param text 검색어(앞뒤 공백뿐이면 거르지 않는다)
     * @param showHidden 숨긴 항목도 보일지
     */
    HistoryTree tree(String text, boolean showHidden);
}
