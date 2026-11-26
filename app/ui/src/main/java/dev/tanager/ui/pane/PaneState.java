package dev.tanager.ui.pane;

import dev.tanager.application.port.in.StartFailure;
import dev.tanager.application.port.in.StartNotice;
import dev.tanager.application.port.in.StartedSession;
import dev.tanager.domain.session.ProfileId;
import dev.tanager.domain.session.SessionState;
import dev.tanager.domain.session.ShellProfile;
import dev.tanager.domain.session.TerminalSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 터미널 창 하나의 화면 상태(Swing 없는 순수 상태 기계, ADR-0011). 해당 없는 사건은 상태 그대로 · 효과 없음이다. 단 기동 중이 아닐 때
 * 도착한 {@link PaneEvent.Started} 의 스트림은 반드시 닫는다(받은 쪽이 소유한다).
 */
sealed interface PaneState {

    /** 셸을 띄우는 중. */
    record Launching(Attempt attempt) implements PaneState {
        public Launching {
            Objects.requireNonNull(attempt, "attempt");
        }
    }

    /** 셸이 돌고 있다. */
    record Running(StartedSession session) implements PaneState {
        public Running {
            Objects.requireNonNull(session, "session");
        }
    }

    /** 셸이 끝났다. Enter 로 다시 띄우고 Tab 으로 다른 셸을 고르고 Esc 로 닫는다. */
    record Exited(TerminalSession session) implements PaneState {
        public Exited {
            Objects.requireNonNull(session, "session");
            if (!(session.state() instanceof SessionState.Exited)) {
                throw new IllegalArgumentException("끝난 세션이어야 한다: " + session.state());
            }
        }
    }

    /** 띄우지 못했다. 다시 시도할 수 있으면 {@code retry} 로 띄운다. 선택이 필요하다는 결과는 {@link Picking} 이 된다. */
    record Failed(StartFailure failure, Attempt retry) implements PaneState {
        public Failed {
            Objects.requireNonNull(failure, "failure");
            Objects.requireNonNull(retry, "retry");
            if (failure instanceof StartFailure.ChoiceRequired) {
                throw new IllegalArgumentException("선택이 필요하다는 결과는 선택 화면이 된다");
            }
        }

        /** 셸이 하나도 없거나 요청이 규칙에 맞지 않으면 다시 해도 같다. */
        boolean retryable() {
            return switch (failure) {
                case StartFailure.LaunchFailed _ -> true;
                case StartFailure.NoShellAvailable _, StartFailure.Rejected _, StartFailure.ChoiceRequired _ -> false;
            };
        }
    }

    /** 기동이 예외로 끝났다({@link PaneEvent.StartErrored}). {@code retry} 로 다시 띄울 수 있다. */
    record Errored(String detail, Attempt retry) implements PaneState {
        public Errored {
            Objects.requireNonNull(detail, "detail");
            Objects.requireNonNull(retry, "retry");
        }
    }

    /** 셸 선택 화면. 고르면 그 셸을 띄우고 Esc 는 {@code exit} 를 따른다. */
    record Picking(ShellPicker picker, PickerExit exit) implements PaneState {
        public Picking {
            Objects.requireNonNull(picker, "picker");
            Objects.requireNonNull(exit, "exit");
        }
    }

    /** 닫혔다. */
    record Closed() implements PaneState {}

    /** {@code event} 를 처리한 다음 상태와 효과. */
    default Transition on(PaneEvent event) {
        if (event instanceof PaneEvent.Started started && !(this instanceof Launching)) {
            return new Transition(this, List.of(new PaneEffect.CloseStream(started.session().stream())));
        }
        return switch (this) {
            case Launching launching -> launching(launching, event);
            case Running running -> running(running, event);
            case Exited exited -> exited(exited, event);
            case Failed failed -> failed(failed, event);
            case Errored errored -> errored(errored, event);
            case Picking picking -> picking(picking, event);
            case Closed closed -> Transition.stay(closed);
        };
    }

    private static Transition launching(Launching state, PaneEvent event) {
        return switch (event) {
            case PaneEvent.Started(StartedSession session) -> {
                List<PaneEffect> effects = new ArrayList<>();
                effects.add(new PaneEffect.Connect(session));
                addNotices(effects, session.notices());
                yield new Transition(new Running(session), effects);
            }
            case PaneEvent.StartFailed(StartFailure.ChoiceRequired required) -> {
                List<PaneEffect> effects = new ArrayList<>();
                addNotices(effects, required.notices());
                Picking picking = new Picking(ShellPicker.atStartup(required.choice()), new PickerExit.CloseWindow());
                yield new Transition(picking, effects);
            }
            case PaneEvent.StartFailed(StartFailure failure) -> {
                List<PaneEffect> effects = new ArrayList<>();
                if (failure instanceof StartFailure.LaunchFailed launchFailed) {
                    addNotices(effects, launchFailed.notices());
                }
                yield new Transition(new Failed(failure, state.attempt()), effects);
            }
            case PaneEvent.StartErrored(String detail) -> Transition.stay(new Errored(detail, state.attempt()));
            case PaneEvent.CloseRequested _ -> Transition.stay(new Closed());
            case PaneEvent.ShellExited _,
                    PaneEvent.EnterPressed _,
                    PaneEvent.EscPressed _,
                    PaneEvent.TabPressed _,
                    PaneEvent.PickerInput _,
                    PaneEvent.ShellsListed _ -> Transition.stay(state);
        };
    }

    private static Transition running(Running state, PaneEvent event) {
        return switch (event) {
            // 셸이 끝나면 JediTerm 이 EOF 에서 커넥터(스트림)를 이미 닫는다. 소유권을 분명히 하려고 한 번 더 닫는다(멱등)
            case PaneEvent.ShellExited(TerminalSession session) ->
                new Transition(new Exited(session), List.of(new PaneEffect.CloseStream(state.session().stream())));
            case PaneEvent.CloseRequested _ ->
                new Transition(new Closed(), List.of(new PaneEffect.CloseStream(state.session().stream())));
            case PaneEvent.Started _,
                    PaneEvent.StartFailed _,
                    PaneEvent.StartErrored _,
                    PaneEvent.EnterPressed _,
                    PaneEvent.EscPressed _,
                    PaneEvent.TabPressed _,
                    PaneEvent.PickerInput _,
                    PaneEvent.ShellsListed _ -> Transition.stay(state);
        };
    }

    private static Transition exited(Exited state, PaneEvent event) {
        return switch (event) {
            case PaneEvent.EnterPressed _ -> {
                Attempt restart = new Attempt.Restart(state.session());
                yield new Transition(new Launching(restart), List.of(new PaneEffect.Launch(restart)));
            }
            case PaneEvent.EscPressed _ -> new Transition(new Closed(), List.of(new PaneEffect.RemovePane()));
            case PaneEvent.CloseRequested _ -> Transition.stay(new Closed());
            case PaneEvent.TabPressed _ -> listShells(state);
            case PaneEvent.ShellsListed(List<ShellProfile> shells) ->
                reopen(state, shells, Optional.of(state.session().profile().id()));
            case PaneEvent.Started _,
                    PaneEvent.StartFailed _,
                    PaneEvent.StartErrored _,
                    PaneEvent.ShellExited _,
                    PaneEvent.PickerInput _ -> Transition.stay(state);
        };
    }

    private static Transition failed(Failed state, PaneEvent event) {
        return switch (event) {
            case PaneEvent.EnterPressed _ ->
                state.retryable()
                        ? new Transition(new Launching(state.retry()), List.of(new PaneEffect.Launch(state.retry())))
                        : Transition.stay(state);
            case PaneEvent.EscPressed _ -> new Transition(new Closed(), List.of(new PaneEffect.RemovePane()));
            case PaneEvent.CloseRequested _ -> Transition.stay(new Closed());
            // 셸이 없음 · 요청 거부는 다른 셸을 골라도 나아지지 않는다
            case PaneEvent.TabPressed _ -> state.retryable() ? listShells(state) : Transition.stay(state);
            case PaneEvent.ShellsListed(List<ShellProfile> shells) ->
                state.retryable() ? reopen(state, shells, failedProfile(state)) : Transition.stay(state);
            case PaneEvent.Started _,
                    PaneEvent.StartFailed _,
                    PaneEvent.StartErrored _,
                    PaneEvent.ShellExited _,
                    PaneEvent.PickerInput _ -> Transition.stay(state);
        };
    }

    private static Transition errored(Errored state, PaneEvent event) {
        return switch (event) {
            case PaneEvent.EnterPressed _ ->
                new Transition(new Launching(state.retry()), List.of(new PaneEffect.Launch(state.retry())));
            case PaneEvent.EscPressed _ -> new Transition(new Closed(), List.of(new PaneEffect.RemovePane()));
            case PaneEvent.CloseRequested _ -> Transition.stay(new Closed());
            case PaneEvent.TabPressed _ -> listShells(state);
            case PaneEvent.ShellsListed(List<ShellProfile> shells) ->
                reopen(state, shells, state.retry().knownProfile().map(ShellProfile::id));
            case PaneEvent.Started _,
                    PaneEvent.StartFailed _,
                    PaneEvent.StartErrored _,
                    PaneEvent.ShellExited _,
                    PaneEvent.PickerInput _ -> Transition.stay(state);
        };
    }

    private static Transition picking(Picking state, PaneEvent event) {
        return switch (event) {
            case PaneEvent.EnterPressed _ -> launch(state.picker().pickSelected());
            case PaneEvent.PickerInput(PickerKey key) ->
                switch (state.picker().on(key)) {
                    case PickerStep.Stay(ShellPicker next) -> Transition.stay(new Picking(next, state.exit()));
                    case PickerStep.Picked(Attempt.Chosen chosen) -> launch(chosen);
                };
            case PaneEvent.EscPressed _ ->
                switch (state.exit()) {
                    case PickerExit.CloseWindow _ -> new Transition(new Closed(), List.of(new PaneEffect.RemovePane()));
                    case PickerExit.ReturnTo(PaneState previous) -> Transition.stay(previous);
                };
            case PaneEvent.CloseRequested _ -> Transition.stay(new Closed());
            case PaneEvent.Started _,
                    PaneEvent.StartFailed _,
                    PaneEvent.StartErrored _,
                    PaneEvent.ShellExited _,
                    PaneEvent.TabPressed _,
                    PaneEvent.ShellsListed _ -> Transition.stay(state);
        };
    }

    private static Transition launch(Attempt attempt) {
        return new Transition(new Launching(attempt), List.of(new PaneEffect.Launch(attempt)));
    }

    private static Transition listShells(PaneState state) {
        return new Transition(state, List.of(new PaneEffect.ListShells()));
    }

    // 목록은 Tab 을 누른 상태에 그대로 있을 때만 쓴다. 그사이 다른 상태가 됐으면 그 상태가 무시한다
    private static Transition reopen(PaneState previous, List<ShellProfile> shells, Optional<ProfileId> current) {
        if (shells.isEmpty()) {
            return Transition.stay(previous);
        }
        return Transition.stay(new Picking(ShellPicker.reopened(shells, current), new PickerExit.ReturnTo(previous)));
    }

    private static Optional<ProfileId> failedProfile(Failed state) {
        return state.failure() instanceof StartFailure.LaunchFailed launch
                ? Optional.of(launch.failed().profile().id())
                : Optional.empty();
    }

    private static void addNotices(List<PaneEffect> effects, List<StartNotice> notices) {
        if (!notices.isEmpty()) {
            effects.add(new PaneEffect.ShowNotices(notices));
        }
    }
}
