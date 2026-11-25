package dev.tanager.ui.pane;

import dev.tanager.application.port.in.StartFailure;
import dev.tanager.application.port.in.StartedSession;
import dev.tanager.domain.session.TerminalSession;
import java.util.Objects;

/** 창 하나에 일어나는 일(셸 기동 결과 · 종료 · 키 · 닫기 요청). */
sealed interface PaneEvent {

    /** 셸이 떴다. 받은 쪽이 스트림을 소유한다. */
    record Started(StartedSession session) implements PaneEvent {
        public Started {
            Objects.requireNonNull(session, "session");
        }
    }

    /** 셸을 띄우지 못했다. */
    record StartFailed(StartFailure failure) implements PaneEvent {
        public StartFailed {
            Objects.requireNonNull(failure, "failure");
        }
    }

    /** 셸이 끝났다({@code Exited} 상태의 세션). */
    record ShellExited(TerminalSession session) implements PaneEvent {
        public ShellExited {
            Objects.requireNonNull(session, "session");
        }
    }

    /**
     * 기동 유스케이스가 계약을 어기고 예외를 던졌다(버그). application 포트의 {@code StartFailure} 를 늘리지 않고 화면 쪽 사건으로
     * 다룬다 — 다시 시도할 수 있다.
     */
    record StartErrored(String detail) implements PaneEvent {
        public StartErrored {
            Objects.requireNonNull(detail, "detail");
        }
    }

    /** 안내 띠 · 실패 화면에서 Enter. */
    record EnterPressed() implements PaneEvent {}

    /** 안내 띠 · 실패 화면에서 Esc. */
    record EscPressed() implements PaneEvent {}

    /** 앱이 끝나 창을 닫는다. */
    record CloseRequested() implements PaneEvent {}
}
