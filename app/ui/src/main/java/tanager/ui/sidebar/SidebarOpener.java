package tanager.ui.sidebar;

import java.util.Optional;
import java.util.function.Consumer;
import tanager.application.port.in.OpenChoices;
import tanager.application.port.out.OpenPlacement;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.session.ProfileId;
import tanager.domain.shared.PaneId;

/** 사이드바가 창을 여는 길(#91). 블로킹 · 오류 알림은 구현이 맡는다 — 사이드바는 EDT 에서 부르기만 한다. */
public interface SidebarOpener {
    /** 아무것도 열지 않는다(창에 연결하기 전 · 테스트). */
    SidebarOpener NONE = new SidebarOpener() {
        @Override
        public void open(WorkspacePath path, Optional<ProfileId> profile, Optional<OpenPlacement> placement) {}

        @Override
        public void openHome(ShellEnvironment env) {}

        @Override
        public void openSame(PaneId pane, OpenPlacement placement) {}

        @Override
        public void choices(WorkspacePath path, Consumer<OpenChoices> onEdt) {}
    };

    /** {@code path} 에서 새 창을 연다. 프로파일 · 자리가 비면 그 환경의 기본 · 설정 자리. */
    void open(WorkspacePath path, Optional<ProfileId> profile, Optional<OpenPlacement> placement);

    /** 환경 {@code env} 의 기본 셸로 새 창을 연다. */
    void openHome(ShellEnvironment env);

    /** 세션 창 {@code pane} 의 {@code placement} 쪽에 같은 셸 · 같은 경로(모르면 그 셸의 시작 경로 → 홈)로 새 창을 연다(#92). */
    void openSame(PaneId pane, OpenPlacement placement);

    /** {@code path} 의 열기 메뉴 후보를 구해 EDT 에서 {@code onEdt} 로 넘긴다. 구하지 못하면 부르지 않는다. */
    void choices(WorkspacePath path, Consumer<OpenChoices> onEdt);
}
