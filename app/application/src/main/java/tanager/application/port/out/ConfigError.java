package tanager.application.port.out;

import java.util.Objects;

/** 설정 파일을 읽거나 쓰지 못한 이유. */
public sealed interface ConfigError {

    /** 사용자에게 보여 줄 한 줄 설명. */
    String describe();

    /** 파일이 있지만 읽지 못했다(권한 · 디렉터리 등). */
    record Unreadable(String detail) implements ConfigError {
        public Unreadable {
            Objects.requireNonNull(detail, "detail");
        }

        @Override
        public String describe() {
            return "설정 파일을 읽지 못했다: " + detail;
        }
    }

    /** JSON 문법이 틀렸거나 루트가 객체가 아니다. */
    record Corrupt(String detail) implements ConfigError {
        public Corrupt {
            Objects.requireNonNull(detail, "detail");
        }

        @Override
        public String describe() {
            return "설정 파일이 손상됐다: " + detail;
        }
    }

    /** 디스크의 파일을 덮어쓰면 사용자의 내용을 잃는다(손상 · 상위 버전). 쓰지 않았다. */
    record WouldOverwrite(String reason) implements ConfigError {
        public WouldOverwrite {
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public String describe() {
            return "설정 파일을 덮어쓰지 않았다: " + reason;
        }
    }

    /** 쓰다가 실패했다. 원래 파일은 그대로다. */
    record WriteFailed(String detail) implements ConfigError {
        public WriteFailed {
            Objects.requireNonNull(detail, "detail");
        }

        @Override
        public String describe() {
            return "설정 파일을 저장하지 못했다: " + detail;
        }
    }
}
