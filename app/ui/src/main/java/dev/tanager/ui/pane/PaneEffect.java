package dev.tanager.ui.pane;

import dev.tanager.application.port.in.StartNotice;
import dev.tanager.application.port.in.StartedSession;
import dev.tanager.application.port.out.TerminalStream;
import java.util.List;
import java.util.Objects;

/** 상태 전이가 요구하는 부수 효과. {@link PaneController} 가 실행한다. */
sealed interface PaneEffect {

    /** 백그라운드에서 셸을 띄운다. */
    record Launch(Attempt attempt) implements PaneEffect {
        public Launch {
            Objects.requireNonNull(attempt, "attempt");
        }
    }

    /** 뜬 셸을 위젯에 붙이고 출력 읽기를 시작한다. */
    record Connect(StartedSession session) implements PaneEffect {
        public Connect {
            Objects.requireNonNull(session, "session");
        }
    }

    /** 사용자에게 알린다(상태줄). */
    record ShowNotices(List<StartNotice> notices) implements PaneEffect {
        public ShowNotices {
            notices = List.copyOf(notices);
        }
    }

    /** 스트림을 닫는다(프로세스 정리). */
    record CloseStream(TerminalStream stream) implements PaneEffect {
        public CloseStream {
            Objects.requireNonNull(stream, "stream");
        }
    }

    /** 창을 화면에서 뺀다(마지막 창이면 앱이 끝난다). */
    record RemovePane() implements PaneEffect {}
}
