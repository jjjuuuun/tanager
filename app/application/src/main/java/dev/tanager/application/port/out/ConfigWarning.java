package dev.tanager.application.port.out;

import java.util.Objects;

/** 설정 파일을 읽을 수는 있었지만 일부를 쓰지 못한 이유. 읽기는 계속된다. */
public sealed interface ConfigWarning {

    /** 사용자에게 보여 줄 한 줄 설명. */
    String describe();

    /** {@code key} 의 값 형식이 틀려 기본값을 썼다. */
    record IgnoredValue(String key, String detail) implements ConfigWarning {
        public IgnoredValue {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(detail, "detail");
        }

        @Override
        public String describe() {
            return "설정 " + key + " 를 무시했다: " + detail;
        }
    }

    /** 이 앱보다 새 버전({@code found})이 쓴 파일이다. 읽을 수 있는 만큼만 읽고 저장하지 않는다. */
    record NewerVersion(int found) implements ConfigWarning {
        @Override
        public String describe() {
            return "새 버전(schemaVersion " + found + ")이 쓴 설정 파일이라 읽기만 한다";
        }
    }
}
