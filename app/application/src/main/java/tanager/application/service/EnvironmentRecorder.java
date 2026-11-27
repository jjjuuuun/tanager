package tanager.application.service;

import java.util.List;
import java.util.Objects;
import tanager.application.port.in.AppEvent;
import tanager.application.port.in.RecordEnvironmentsUseCase;
import tanager.application.state.StateActor;
import tanager.application.state.Transition;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.session.ShellProfile;

/** 셸 탐지 결과에서 환경 순서를 뽑아 상태에 둔다(#83). 탐지 스레드에서 불린다 — 응답을 기다리지 않는다. */
public final class EnvironmentRecorder implements RecordEnvironmentsUseCase {
    private final StateActor state;

    public EnvironmentRecorder(StateActor state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    @Override
    public void profilesDetected(List<ShellProfile> profiles) {
        List<ShellEnvironment> order =
                profiles.stream().map(ShellProfile::env).distinct().toList();
        state.submit(current -> current.environments().equals(order)
                ? new Transition<>(current, List.of(), false)
                : new Transition<>(
                        current.withEnvironments(order), List.of(new AppEvent.EnvironmentsChanged(order)), true));
    }
}
