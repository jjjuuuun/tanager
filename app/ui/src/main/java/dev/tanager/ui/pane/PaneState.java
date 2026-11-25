package dev.tanager.ui.pane;

import dev.tanager.application.port.in.StartFailure;
import dev.tanager.application.port.in.StartNotice;
import dev.tanager.application.port.in.StartedSession;
import dev.tanager.domain.session.SessionState;
import dev.tanager.domain.session.TerminalSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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

    /** 셸이 끝났다. Enter 로 다시 띄우고 Esc 로 닫는다. */
    record Exited(TerminalSession session) implements PaneState {
        public Exited {
            Objects.requireNonNull(session, "session");
            if (!(session.state() instanceof SessionState.Exited)) {
                throw new IllegalArgumentException("끝난 세션이어야 한다: " + session.state());
            }
        }
    }

    /** 띄우지 못했다. 다시 시도할 수 있으면 {@code retry} 로 띄운다. */
    record Failed(StartFailure failure, Attempt retry) implements PaneState {
        public Failed {
            Objects.requireNonNull(failure, "failure");
            Objects.requireNonNull(retry, "retry");
        }

        /** 셸이 하나도 없거나 요청이 규칙에 맞지 않으면 다시 해도 같다. */
        boolean retryable() {
            return switch (failure) {
                case StartFailure.LaunchFailed _ -> true;
                case StartFailure.NoShellAvailable _, StartFailure.Rejected _ -> false;
            };
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
            case PaneEvent.StartFailed(StartFailure failure) -> {
                List<PaneEffect> effects = new ArrayList<>();
                if (failure instanceof StartFailure.LaunchFailed launchFailed) {
                    addNotices(effects, launchFailed.notices());
                }
                yield new Transition(new Failed(failure, state.attempt()), effects);
            }
            case PaneEvent.CloseRequested _ -> Transition.stay(new Closed());
            case PaneEvent.ShellExited _, PaneEvent.EnterPressed _, PaneEvent.EscPressed _ -> Transition.stay(state);
        };
    }

    private static Transition running(Running state, PaneEvent event) {
        return switch (event) {
            case PaneEvent.ShellExited(TerminalSession session) -> Transition.stay(new Exited(session));
            case PaneEvent.CloseRequested _ ->
                new Transition(new Closed(), List.of(new PaneEffect.CloseStream(state.session().stream())));
            case PaneEvent.Started _, PaneEvent.StartFailed _, PaneEvent.EnterPressed _, PaneEvent.EscPressed _ ->
                Transition.stay(state);
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
            case PaneEvent.Started _, PaneEvent.StartFailed _, PaneEvent.ShellExited _ -> Transition.stay(state);
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
            case PaneEvent.Started _, PaneEvent.StartFailed _, PaneEvent.ShellExited _ -> Transition.stay(state);
        };
    }

    private static void addNotices(List<PaneEffect> effects, List<StartNotice> notices) {
        if (!notices.isEmpty()) {
            effects.add(new PaneEffect.ShowNotices(notices));
        }
    }
}
