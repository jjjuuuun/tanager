package tanager.application.state;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import tanager.application.port.in.AppEvent;
import tanager.domain.history.WorkspacePath;

/**
 * 이력 경로 하나가 있는지 확인한 결과를 반영하는 명령(FR-HIS-08, #186). 없으면 사라진 경로 집합에 넣고 있으면 뺀다. 확인하는 동안 이력에서
 * 지워진 경로는 넣지 않는다. 집합이 바뀌었을 때만 {@link AppEvent.MissingPathsChanged} 를 낸다. 응답은 바꿨는가.
 */
public record MissingPathChecked(WorkspacePath path, boolean exists) implements Command<Boolean> {
    public MissingPathChecked {
        Objects.requireNonNull(path, "path");
    }

    @Override
    public Transition<Boolean> apply(AppState current) {
        Set<WorkspacePath> next = new HashSet<>(current.missing());
        if (exists) {
            next.remove(path);
        } else if (current.history().find(path).isPresent()) {
            next.add(path);
        }
        if (next.equals(current.missing())) {
            return new Transition<>(current, List.of(), false);
        }
        return new Transition<>(current.withMissing(next), List.of(new AppEvent.MissingPathsChanged(next)), true);
    }
}
