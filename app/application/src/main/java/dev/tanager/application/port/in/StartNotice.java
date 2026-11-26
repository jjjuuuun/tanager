package dev.tanager.application.port.in;

import dev.tanager.application.port.out.ConfigError;
import dev.tanager.application.port.out.ConfigWarning;
import dev.tanager.domain.history.WorkspacePath;
import dev.tanager.domain.session.ProfileId;
import java.util.Objects;

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

    /** 시작 경로 {@code requested} 가 없어 홈에서 띄웠다. */
    record StartDirMissing(WorkspacePath requested) implements StartNotice {
        public StartDirMissing {
            Objects.requireNonNull(requested, "requested");
        }

        @Override
        public String describe() {
            return "시작 경로 " + requested.path() + " 가 없어 홈에서 시작했다";
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
    record IntegrationUnavailable(String reason) implements StartNotice {
        public IntegrationUnavailable {
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public String describe() {
            return "셸 통합을 쓸 수 없어 이동한 경로를 기록하지 않는다 — " + reason;
        }
    }
}
