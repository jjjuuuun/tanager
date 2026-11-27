package tanager.ui.layout;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tanager.domain.shared.PaneId;
import tanager.domain.workspace.Workspace;

/**
 * 작업 공간이 바뀔 때 새로 만들 창과 없앨 창(순수, #89). 명세가 바뀐 창은 없애고 다시 만든다.
 *
 * @param added 다음 작업 공간의 창 순서대로
 * @param removed 이전 작업 공간의 창 순서대로
 */
public record PaneDiff(List<PaneId> added, List<PaneId> removed) {
    public PaneDiff {
        added = List.copyOf(added);
        removed = List.copyOf(removed);
    }

    /** {@code previous} → {@code next}. */
    public static PaneDiff between(Workspace previous, Workspace next) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(next, "next");
        List<PaneId> added = new ArrayList<>();
        for (PaneId pane : next.tab().root().panes()) {
            if (!previous.spec(pane).equals(next.spec(pane))) {
                added.add(pane);
            }
        }
        List<PaneId> removed = new ArrayList<>();
        for (PaneId pane : previous.tab().root().panes()) {
            if (!previous.spec(pane).equals(next.spec(pane))) {
                removed.add(pane);
            }
        }
        return new PaneDiff(added, removed);
    }

    /** 바뀐 창이 없다(포커스 · 비율만 바뀜). */
    public boolean isEmpty() {
        return added.isEmpty() && removed.isEmpty();
    }
}
