package tanager.ui.sidebar;

import java.util.Optional;

/** 메뉴 동작이 사용자 · 시스템과 주고받는 것(클립보드 · 확인 · 별칭 입력). 테스트에서 바꿔 끼운다. */
interface SidebarInteractions {

    /** 클립보드에 글자를 넣는다. */
    void copy(String text);

    /** 기록 삭제를 확인한다. {@code true} 면 지운다. */
    boolean confirmForget(String label);

    /** 별칭을 묻는다. 비어 있으면 취소, 안의 값이 비어 있으면 별칭을 지운다. */
    Optional<Optional<String>> editAlias(Optional<String> current);
}
