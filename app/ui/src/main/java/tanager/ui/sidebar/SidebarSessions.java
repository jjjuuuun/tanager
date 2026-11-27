package tanager.ui.sidebar;

import tanager.domain.shared.PaneId;

/** 사이드바가 세션 노드에서 그 창에 부탁하는 일(#92). 메인 창이 구현한다. 모든 메서드는 EDT 에서 부른다. */
public interface SidebarSessions {
    /** 아무것도 하지 않는다(창에 연결하기 전 · 테스트). */
    SidebarSessions NONE = new SidebarSessions() {
        @Override
        public void focus(PaneId pane) {}

        @Override
        public void restart(PaneId pane) {}

        @Override
        public void close(PaneId pane) {}
    };

    /** {@code pane} 에 포커스하고 키보드 포커스를 그 터미널로 옮긴다. */
    void focus(PaneId pane);

    /** 끝난 {@code pane} 의 셸을 다시 띄운다(끝난 창의 Enter 와 같다). 끝나지 않았으면 아무것도 하지 않는다. */
    void restart(PaneId pane);

    /** {@code pane} 을 닫는다. 실행 중인 셸도 묻지 않고 닫고, 마지막 창이면 앱이 끝난다. */
    void close(PaneId pane);
}
