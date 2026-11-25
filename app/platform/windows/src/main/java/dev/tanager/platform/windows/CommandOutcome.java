package dev.tanager.platform.windows;

import java.time.Duration;
import java.util.Objects;

/** 외부 명령 실행 결과. */
sealed interface CommandOutcome {

    /** 정해진 시간 안에 끝났다. 표준 출력은 바이트 그대로(인코딩은 호출 쪽이 정한다). */
    record Exited(int code, byte[] stdout) implements CommandOutcome {
        public Exited {
            stdout = stdout.clone();
        }

        @Override
        public byte[] stdout() {
            return stdout.clone();
        }
    }

    /** 프로세스를 띄우지 못했다(실행 파일 없음 등). */
    record NotStarted(String reason) implements CommandOutcome {
        public NotStarted {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** 시간 안에 끝나지 않아 강제 종료했다. */
    record TimedOut(Duration after) implements CommandOutcome {
        public TimedOut {
            Objects.requireNonNull(after, "after");
        }
    }
}
