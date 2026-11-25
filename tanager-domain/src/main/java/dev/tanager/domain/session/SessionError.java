package dev.tanager.domain.session;

import dev.tanager.domain.history.ShellEnvironment;
import java.util.Objects;

/** 세션 전이를 거부한 이유. */
public sealed interface SessionError {

    /** 현재 상태 {@code from} 에서 {@code event} 는 허용되지 않는다. */
    record IllegalTransition(SessionState from, String event) implements SessionError {
        public IllegalTransition {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(event, "event");
        }
    }

    /** 프로파일 환경({@code expected})과 다른 환경({@code actual})의 경로가 들어왔다. */
    record EnvironmentMismatch(ShellEnvironment expected, ShellEnvironment actual) implements SessionError {
        public EnvironmentMismatch {
            Objects.requireNonNull(expected, "expected");
            Objects.requireNonNull(actual, "actual");
        }
    }
}
