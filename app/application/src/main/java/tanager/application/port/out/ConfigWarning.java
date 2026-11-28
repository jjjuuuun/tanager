package tanager.application.port.out;

import java.util.Objects;

/** 설정 파일을 읽을 수는 있었지만 일부를 쓰지 못한 이유. 읽기는 계속된다. */
public sealed interface ConfigWarning {

    /** 사용자에게 보여 줄 한 줄 설명. */
    String describe();

    /** {@code key} 의 값 형식이 틀려 기본값을 썼다. */
    record IgnoredValue(String key, IgnoredReason reason) implements ConfigWarning {
        public IgnoredValue {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public String describe() {
            return "설정 " + key + " 를 무시했다: " + reason;
        }
    }

    /** 이 앱보다 새 버전({@code found})이 쓴 파일이다. 읽을 수 있는 만큼만 읽고 저장하지 않는다. */
    record NewerVersion(int found) implements ConfigWarning {
        @Override
        public String describe() {
            return "새 버전(schemaVersion " + found + ")이 쓴 설정 파일이라 읽기만 한다";
        }
    }

    /**
     * {@code key} 의 값 {@code found} 가 허용 범위 밖이라 {@code used} 를 썼다. 보통 가까운 경계이고, 경계가 열린 쪽(임계값 0 이하)이면
     * 기본값이다. 두 값 모두 파일에 쓰는 형식의 원문이다.
     */
    record OutOfRange(String key, String found, String used) implements ConfigWarning {
        public OutOfRange {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(found, "found");
            Objects.requireNonNull(used, "used");
        }

        @Override
        public String describe() {
            return "설정 " + key + " 의 값 " + found + " 이 범위 밖이라 " + used + " 를 쓴다";
        }
    }

    /** 설정 값을 쓰지 못한 이유. */
    enum IgnoredReason {
        /** 1 이상의 정수가 아니다. */
        NOT_POSITIVE_INTEGER,
        /** JSON 객체가 아니다. */
        NOT_OBJECT,
        /** 프로파일 id 가 비어 있다. */
        EMPTY_PROFILE_ID,
        /** 비어 있지 않은 문자열이 아니다. */
        NOT_NON_EMPTY_STRING,
        /** 아는 값이 아니다. */
        UNKNOWN_VALUE,
        /** 정수가 아니다. */
        NOT_INTEGER,
        /** 숫자가 아니다. */
        NOT_NUMBER,
        /** true · false 가 아니다. */
        NOT_BOOLEAN,
        /** JSON 배열이 아니다. */
        NOT_ARRAY,
        /** 문자열(또는 null)이 아니다. */
        NOT_STRING,
        /** 이름(키)이 비어 있다. */
        EMPTY_NAME,
        /** 절대 경로 glob 이 아니다(이력 제외 규칙, #186). */
        INVALID_GLOB,
        /** 공급자 id 형식({@code [a-z0-9][a-z0-9-]{0,31}})이 아니다(도구함 공급자 목록, #206). */
        INVALID_PROVIDER_ID
    }
}
