package tanager.application.port.in;

import java.util.Objects;
import tanager.application.port.out.ConfigError;
import tanager.application.port.out.ConfigWarning;
import tanager.application.port.out.Integration;
import tanager.domain.history.WorkspacePath;
import tanager.domain.session.ProfileId;
import tanager.domain.session.ShellLine;

/** 세션은 떴지만 사용자에게 알릴 것. */
public sealed interface StartNotice {

    /** 사용자에게 보여 줄 한 줄 설명. */
    String describe();

    /** 설정 파일을 읽지 못해 기본값으로 띄웠다. */
    record ConfigUnreadable(ConfigError error) implements StartNotice {
        public ConfigUnreadable {
            Objects.requireNonNull(error, "error");
        }

        @Override
        public String describe() {
            return error.describe() + " — 기본 설정으로 시작했다";
        }
    }

    /** 설정을 읽다 경고가 났다. */
    record ConfigWarned(ConfigWarning warning) implements StartNotice {
        public ConfigWarned {
            Objects.requireNonNull(warning, "warning");
        }

        @Override
        public String describe() {
            return warning.describe();
        }
    }

    /** 설정의 시작 경로 {@code raw} 가 그 환경의 절대 경로가 아니라 홈에서 띄웠다. */
    record StartDirInvalid(String raw) implements StartNotice {
        public StartDirInvalid {
            Objects.requireNonNull(raw, "raw");
        }

        @Override
        public String describe() {
            return "시작 경로 " + raw + " 를 쓸 수 없어 홈에서 시작했다";
        }
    }

    /**
     * 시작 경로 {@code requested}(설정 · 트리에서 연 경로 · 복원한 창의 마지막 cwd)가 없어 홈에서 열었다(SDD §6.2, #179). Windows 는 기동이
     * 경로 없음을 알려서, WSL 은 기동 전 확인으로 안다.
     */
    record MissingDirectory(WorkspacePath requested) implements StartNotice {
        public MissingDirectory {
            Objects.requireNonNull(requested, "requested");
        }

        @Override
        public String describe() {
            return requested.path() + " 이 없어 홈에서 열었다";
        }
    }

    /** 셸은 띄웠지만 기본 셸로 저장하지 못했다. 설정 파일은 그대로다. */
    record DefaultNotSaved(ProfileId profile, ConfigError error) implements StartNotice {
        public DefaultNotSaved {
            Objects.requireNonNull(profile, "profile");
            Objects.requireNonNull(error, "error");
        }

        @Override
        public String describe() {
            return "기본 셸 " + profile.value() + " 을 저장하지 못했다 — " + error.describe();
        }
    }

    /** 셸 통합을 쓸 수 없어 이 셸의 현재 경로를 추적하지 않는다 — 시작 경로만 기록한다. */
    record IntegrationUnavailable(Integration.Problem problem) implements StartNotice {
        public IntegrationUnavailable {
            Objects.requireNonNull(problem, "problem");
        }

        @Override
        public String describe() {
            return "셸 통합을 쓸 수 없어 이동한 경로를 기록하지 않는다 — " + problem.describe();
        }
    }

    /**
     * 새 창에 달린 시작 명령 {@code line}(예: {@code claude --resume <id>}, FR-TK-09)을 셸에 쓰지 않고 버렸다(#216). 세션이 뜬 뒤에 정해지므로
     * {@link AppEvent.SessionNotice} 로 온다.
     */
    record StartupCommandSkipped(ShellLine line, Reason reason) implements StartNotice {
        public StartupCommandSkipped {
            Objects.requireNonNull(line, "line");
            Objects.requireNonNull(reason, "reason");
        }

        /** 버린 이유. */
        public enum Reason {
            /** 셸이 처음 알린(통합이 없으면 띄운) 경로가 요청한 폴더가 아니다 — 경로가 사라져 홈에서 떴을 때 등. */
            PATH_MISMATCH,
            /** 셸이 첫 프롬프트 전에 끝났다. */
            SHELL_EXITED,
            /** 정한 시간 안에 셸이 경로를 알리지 않았다. */
            TIMED_OUT
        }

        // 명령 내용은 로그에 남기지 않는다(java-style 12)
        @Override
        public String describe() {
            return "새 창의 시작 명령을 실행하지 않았다 — " + reason;
        }
    }
}
