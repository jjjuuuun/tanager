package tanager.domain.workspace;

import java.util.Objects;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.TabId;

/** 탭 연산을 할 수 없는 이유(#176, FR-LAY-07 · 10 · 12). */
public sealed interface TabError {

    /** 그 탭이 없다. */
    record UnknownTab(TabId tab) implements TabError {
        public UnknownTab {
            Objects.requireNonNull(tab, "tab");
        }
    }

    /** 그 창이 어느 탭에도 없다. */
    record UnknownPane(PaneId pane) implements TabError {
        public UnknownPane {
            Objects.requireNonNull(pane, "pane");
        }
    }

    /** 마지막 탭은 닫을 수 없다(앱을 끝내는 길은 마지막 창 닫기의 {@code LayoutError.LastPane} 하나다). */
    record LastTab(TabId tab) implements TabError {
        public LastTab {
            Objects.requireNonNull(tab, "tab");
        }
    }

    /** 그 번호(0 부터)의 탭이 없다. */
    record NoSuchIndex(int index) implements TabError {}

    /** 탭의 유일한 창이라 새 탭으로 옮겨도 달라지는 게 없다. */
    record SolePane(PaneId pane) implements TabError {
        public SolePane {
            Objects.requireNonNull(pane, "pane");
        }
    }

    /** 창이 이미 그 탭에 있다. */
    record SameTab(TabId tab) implements TabError {
        public SameTab {
            Objects.requireNonNull(tab, "tab");
        }
    }

    /** 탭 이름으로 쓸 수 없다({@link TabName#of}). */
    record InvalidName(TabNameProblem problem) implements TabError {
        public InvalidName {
            Objects.requireNonNull(problem, "problem");
        }
    }
}
