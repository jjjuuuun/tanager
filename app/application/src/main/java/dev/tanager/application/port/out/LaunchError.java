package dev.tanager.application.port.out;

import java.util.Objects;

/** {@link PtyLauncher#launch} 가 프로세스를 띄우지 못한 이유. */
public sealed interface LaunchError {

    /** 사용자에게 보여 줄 한 줄 설명. */
    String describe();

    /** 절대 경로로 준 실행 파일이 없다. */
    record ExecutableNotFound(String executable) implements LaunchError {
        public ExecutableNotFound {
            Objects.requireNonNull(executable, "executable");
        }

        @Override
        public String describe() {
            return "실행 파일을 찾을 수 없다: " + executable;
        }
    }

    /** 작업 디렉터리가 없거나 디렉터리가 아니다. */
    record WorkingDirectoryNotFound(String directory) implements LaunchError {
        public WorkingDirectoryNotFound {
            Objects.requireNonNull(directory, "directory");
        }

        @Override
        public String describe() {
            return "작업 디렉터리를 찾을 수 없다: " + directory;
        }
    }

    /** 그 밖에 PTY 나 프로세스를 만들지 못했다(네이티브 라이브러리 로드 실패 포함). */
    record StartFailed(String detail) implements LaunchError {
        public StartFailed {
            Objects.requireNonNull(detail, "detail");
        }

        @Override
        public String describe() {
            return "터미널을 시작하지 못했다: " + detail;
        }
    }
}
