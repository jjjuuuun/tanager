package tanager.ui.layout;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tanager.domain.shared.PaneId;
import tanager.domain.workspace.Tab;
import tanager.domain.workspace.Workspace;

/**
 * 작업 공간이 바뀔 때 새로 만들 창과 없앨 창(순수, #89). 명세가 바뀐 창은 없애고 다시 만든다.
 *
 * @param added 다음 작업 공간의 창 순서대로(탭 순서 → 트리 순서)
 * @param removed 이전 작업 공간의 창 순서대로(탭 순서 → 트리 순서)
 */
public record PaneDiff(List<PaneId> added, List<PaneId> removed) {
    public PaneDiff {
        added = List.copyOf(added);
        removed = List.copyOf(removed);
    }

    /** {@code previous} → {@code next}. 모든 탭의 창을 본다 — 다른 탭에서 닫힌 창의 셸도 닫는다(#177). */
    public static PaneDiff between(Workspace previous, Workspace next) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(next, "next");
        return new PaneDiff(changed(next, previous), changed(previous, next));
    }

    // from 의 창 중 other 에서 명세가 다른(없어진 것 포함) 창. 탭 순서 · 트리 순서대로
    private static List<PaneId> changed(Workspace from, Workspace other) {
        List<PaneId> panes = new ArrayList<>();
        for (Tab tab : from.tabs()) {
            for (PaneId pane : tab.root().panes()) {
                if (!from.spec(pane).equals(other.spec(pane))) {
                    panes.add(pane);
                }
            }
        }
        return panes;
    }

    /** 바뀐 창이 없다(포커스 · 비율만 바뀜). */
    public boolean isEmpty() {
        return added.isEmpty() && removed.isEmpty();
    }
}
