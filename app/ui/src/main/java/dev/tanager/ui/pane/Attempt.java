package dev.tanager.ui.pane;

import dev.tanager.domain.session.SessionState;
import dev.tanager.domain.session.TerminalSession;
import java.util.Objects;

/** 셸을 띄우는 한 번의 시도. 실패하면 같은 시도로 다시 띄운다. */
sealed interface Attempt {

    /** 설정 · 기본 규칙으로 새로 띄운다. */
    record Fresh() implements Attempt {}

    /** 끝난 세션을 같은 프로파일로 마지막 경로에서 다시 띄운다. */
    record Restart(TerminalSession exited) implements Attempt {
        public Restart {
            Objects.requireNonNull(exited, "exited");
            if (!(exited.state() instanceof SessionState.Exited)) {
                throw new IllegalArgumentException("다시 띄울 세션은 Exited 여야 한다: " + exited.state());
            }
        }
    }
}
