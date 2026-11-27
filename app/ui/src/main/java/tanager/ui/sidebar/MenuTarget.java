package tanager.ui.sidebar;

import java.util.Objects;
import java.util.Optional;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.VisitedDirectory;
import tanager.domain.history.WorkspacePath;
import tanager.domain.shared.PaneId;

/** 메뉴를 연 노드가 무엇인지(메뉴 항목을 정하는 데 필요한 값만). */
sealed interface MenuTarget {

    /** 폴더(고정 항목 포함). 방문 기록이 없으면(압축 중간 · 세션만 있는 폴더) 경로 복사만 된다. */
    record Folder(WorkspacePath path, Optional<VisitedDirectory> visit) implements MenuTarget {
        public Folder {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(visit, "visit");
        }
    }

    /** 환경 노드. {@code showHidden} 은 지금 숨긴 폴더를 보이는 중인지. */
    record Environment(ShellEnvironment env, boolean showHidden) implements MenuTarget {
        public Environment {
            Objects.requireNonNull(env, "env");
        }
    }

    /**
     * 세션. 경로를 모르면 복사할 것이 없다.
     *
     * @param pane 그 세션의 창(포커스 · 새 창 · 재시작 · 닫기의 대상, #92)
     * @param exited 셸이 끝났는지(재시작은 끝난 세션만)
     */
    record Session(PaneId pane, Optional<WorkspacePath> cwd, boolean exited) implements MenuTarget {
        public Session {
            Objects.requireNonNull(pane, "pane");
            Objects.requireNonNull(cwd, "cwd");
        }
    }
}
