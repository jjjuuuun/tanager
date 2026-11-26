package dev.tanager.application.port.in;

import dev.tanager.domain.history.WorkspacePath;
import dev.tanager.domain.shared.PaneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link RecordVisitUseCase} 의 수작업 Fake. 받은 보고를 순서대로 기록하고, 예약해 둔 예외를 한 번 던진다(던질 때도 기록한다). 스레드 안전.
 */
public final class RecordingVisits implements RecordVisitUseCase {
    /** 보고 하나. */
    public record Visit(PaneId pane, WorkspacePath cwd) {}

    private final List<Visit> visits = new ArrayList<>();
    private Optional<RuntimeException> nextFailure = Optional.empty();

    /** 다음 {@code record} 한 번이 이 예외를 던진다. */
    public synchronized RecordingVisits failNextWith(RuntimeException failure) {
        this.nextFailure = Optional.of(failure);
        return this;
    }

    @Override
    public void record(PaneId pane, WorkspacePath cwd) {
        Optional<RuntimeException> failure;
        synchronized (this) {
            visits.add(new Visit(pane, cwd));
            failure = nextFailure;
            nextFailure = Optional.empty();
        }
        if (failure.isPresent()) {
            throw failure.get();
        }
    }

    public synchronized List<Visit> visits() {
        return List.copyOf(visits);
    }
}
