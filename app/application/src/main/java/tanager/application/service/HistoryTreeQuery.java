package tanager.application.service;

import java.util.Objects;
import tanager.application.port.in.QueryHistoryTreeUseCase;
import tanager.application.state.AppState;
import tanager.application.state.StateActor;
import tanager.domain.history.DirectoryTree;
import tanager.domain.history.HistoryTree;
import tanager.domain.history.TreeQuery;

/** 확정된 상태({@link StateActor#snapshot()}) 위에서 트리를 투영한다(#83). 명령 도중의 상태는 보지 않는다. */
public final class HistoryTreeQuery implements QueryHistoryTreeUseCase {
    private final StateActor state;

    public HistoryTreeQuery(StateActor state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    @Override
    public HistoryTree tree(String text, boolean showHidden) {
        Objects.requireNonNull(text, "text");
        AppState current = state.snapshot();
        return DirectoryTree.project(
                current.history(),
                current.sessions().leaves(),
                new TreeQuery(text, showHidden, current.environments()));
    }
}
