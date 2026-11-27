package tanager.application.port.out;

import java.nio.file.Path;
import java.util.Objects;

/** 셸 통합 설치 결과(ADR-0014). 기동 명령은 이 값을 보고 스크립트를 주입할지 정한다. */
public sealed interface Integration {

    /** 스크립트가 {@code directory} 에 설치돼 있다. */
    record Installed(Path directory) implements Integration {
        public Installed {
            Objects.requireNonNull(directory, "directory");
        }
    }

    /** 셸 통합을 쓸 수 없다. 화면 문장은 UI 가 {@code problem} 으로 만든다(#137). */
    record Unavailable(Problem problem) implements Integration {
        public Unavailable {
            Objects.requireNonNull(problem, "problem");
        }
    }

    /** 셸 통합을 쓸 수 없는 이유. */
    sealed interface Problem {
        /** 개발자용 설명(로그). */
        String describe();

        /** 이 플랫폼은 아직 셸 통합이 없다. */
        record NotSupported() implements Problem {
            @Override
            public String describe() {
                return "이 플랫폼은 셸 통합을 지원하지 않는다";
            }
        }

        /**
         * 스크립트를 {@code path} 에 설치하지 못했다.
         *
         * @param cause 알려진 파일 문제, 모르면 {@link FileProblem#OTHER}
         * @param detail OS 가 준 이유(예외 메시지 · 클래스 이름). 비어 있을 수 있다
         */
        record InstallFailed(String path, FileProblem cause, String detail) implements Problem {
            public InstallFailed {
                Objects.requireNonNull(path, "path");
                Objects.requireNonNull(cause, "cause");
                Objects.requireNonNull(detail, "detail");
            }

            @Override
            public String describe() {
                return "셸 통합 스크립트를 설치하지 못했다: " + path + " (" + cause + (detail.isEmpty() ? "" : " " + detail) + ")";
            }
        }

        /** 설치가 예외 {@code exception}(클래스 이름)으로 끝났다 — 포트 계약 위반. */
        record Crashed(String exception) implements Problem {
            public Crashed {
                Objects.requireNonNull(exception, "exception");
            }

            @Override
            public String describe() {
                return "셸 통합 설치가 예외로 끝났다: " + exception;
            }
        }
    }

    /** 파일 시스템에서 흔한 설치 실패. */
    enum FileProblem {
        ACCESS_DENIED,
        FILE_EXISTS,
        DIRECTORY_EXISTS,
        NO_SUCH_PATH,
        NOT_DIRECTORY,
        OTHER
    }
}
