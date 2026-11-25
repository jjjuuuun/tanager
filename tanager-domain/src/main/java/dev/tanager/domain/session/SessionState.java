package dev.tanager.domain.session;

import dev.tanager.domain.history.WorkspacePath;
import java.util.Objects;

/** 세션 생명주기: {@code Starting → Running → Exited}, 기동 실패는 {@code Failed}. 각 상태는 복원에 쓸 경로를 가진다. */
public sealed interface SessionState {

    /** 프로세스를 띄우는 중. {@code dir} 에서 시작한다. */
    record Starting(WorkspacePath dir) implements SessionState {
        public Starting {
            Objects.requireNonNull(dir, "dir");
        }
    }

    /** 실행 중. {@code cwd} 는 셸 통합이 마지막으로 알린 경로(알림 전에는 시작 경로). */
    record Running(long pid, WorkspacePath cwd) implements SessionState {
        public Running {
            Objects.requireNonNull(cwd, "cwd");
        }
    }

    /** 셸이 끝났다. {@code lastCwd} 에서 다시 띄울 수 있다. */
    record Exited(int exitCode, WorkspacePath lastCwd) implements SessionState {
        public Exited {
            Objects.requireNonNull(lastCwd, "lastCwd");
        }
    }

    /** 프로세스를 띄우지 못했다. */
    record Failed(String reason) implements SessionState {
        public Failed {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
