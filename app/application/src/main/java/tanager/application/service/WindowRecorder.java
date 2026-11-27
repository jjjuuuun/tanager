package tanager.application.service;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import tanager.application.port.in.AppEvent;
import tanager.application.port.in.RecordWindowStateUseCase;
import tanager.application.state.StateActor;
import tanager.application.state.Transition;
import tanager.domain.workspace.WindowState;

/** 메인 창 상태를 상태에 둔다(FR-RES-03, #180). 저장은 {@link WorkspaceSaver} 가 {@link AppEvent.WindowChanged} 를 보고 한다. */
public final class WindowRecorder implements RecordWindowStateUseCase {
    private final StateActor state;

    public WindowRecorder(StateActor state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    @Override
    public CompletionStage<Void> record(WindowState next) {
        Objects.requireNonNull(next, "next");
        return state.submit(current -> current.window().equals(next)
                        ? new Transition<>(current, List.of(), false)
                        : new Transition<>(current.withWindow(next), List.of(new AppEvent.WindowChanged(next)), true))
                .thenApply(changed -> null);
    }
}
