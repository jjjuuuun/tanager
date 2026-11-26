package dev.tanager.application.port.out;

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

    /** 읽기 전용으로 열려 쓰지 않았다(상위 버전 · 다른 인스턴스 등). */
    record ReadOnly(String reason) implements HistoryStoreError {
        public ReadOnly {
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public String describe() {
            return "이력 파일을 저장하지 않았다: " + reason;
        }
    }
}
