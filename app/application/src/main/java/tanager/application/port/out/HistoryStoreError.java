package tanager.application.port.out;

import java.util.Objects;

/** 이력을 읽거나 쓰지 못한 이유. */
public sealed interface HistoryStoreError {

    /** 사용자에게 보여 줄 한 줄 설명. */
    String describe();

    /** 파일 입출력이 실패했다. 기존 파일은 그대로다. */
    record Io(String detail) implements HistoryStoreError {
        public Io {
            Objects.requireNonNull(detail, "detail");
        }

        @Override
        public String describe() {
            return "이력 파일을 읽거나 쓰지 못했다: " + detail;
        }
    }

    /** 읽기 전용이라 쓰지 않았다. */
    record ReadOnly(ReadOnlyReason reason) implements HistoryStoreError {
        public ReadOnly {
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public String describe() {
            return "이력 파일을 저장하지 않았다: " + reason;
        }
    }

    /** 이력 파일을 쓰지 않는 이유. */
    enum ReadOnlyReason {
        /** 아직 불러오지 않았다. */
        NOT_LOADED,
        /** 불러오기가 실패했다 — 모르는 파일을 덮어쓰지 않는다. */
        LOAD_FAILED,
        /** 저장소를 닫았다. */
        CLOSED,
        /** 이 앱보다 새 버전이 쓴 파일이다. */
        NEWER_SCHEMA,
        /** 다른 Tanager 가 쓰고 있다. */
        OTHER_INSTANCE,
        /** 잠금 파일을 열 수 없다. */
        LOCK_UNAVAILABLE
    }
}
