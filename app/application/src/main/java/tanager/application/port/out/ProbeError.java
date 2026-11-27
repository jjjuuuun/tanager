package tanager.application.port.out;

import java.time.Duration;
import java.util.Objects;

/** WSL 지표를 읽지 못한 이유({@link ResourceProbe#wsl}). 화면 문장은 UI 가 만든다(#137). */
public sealed interface ProbeError {

    /** 개발자용 설명(로그). */
    String describe();

    /** 시간 안에 끝나지 않아 프로세스를 끝냈다(R9). */
    record TimedOut(Duration after) implements ProbeError {
        public TimedOut {
            Objects.requireNonNull(after, "after");
        }

        @Override
        public String describe() {
            return "WSL 지표 수집이 " + after.toMillis() + "ms 안에 끝나지 않았다";
        }
    }

    /** 명령을 띄우지 못했거나 0 이 아닌 코드로 끝났다. */
    record Failed(String reason) implements ProbeError {
        public Failed {
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public String describe() {
            return "WSL 지표 수집 실패: " + reason;
        }
    }

    /** 출력을 읽을 수 없다(필드 · 줄이 모자람). */
    record Unreadable(String reason) implements ProbeError {
        public Unreadable {
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public String describe() {
            return "WSL 지표 출력을 읽지 못했다: " + reason;
        }
    }

    /** 이 OS 에는 WSL 이 없다. */
    record Unsupported() implements ProbeError {
        @Override
        public String describe() {
            return "이 OS 는 WSL 지표를 지원하지 않는다";
        }
    }
}
