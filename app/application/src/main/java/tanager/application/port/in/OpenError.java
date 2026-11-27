package tanager.application.port.in;

import java.util.Objects;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.session.ProfileId;

/** 경로에서 창을 열 수 없는 이유(#88). */
public sealed interface OpenError {

    /** 그 경로의 환경을 띄울 셸이 탐지 목록에 없다(예: 이력에 남은 지운 WSL 배포판). */
    record NoProfileForEnvironment(ShellEnvironment env) implements OpenError {
        public NoProfileForEnvironment {
            Objects.requireNonNull(env, "env");
        }
    }

    /** 지정한 프로파일의 환경이 경로의 환경과 다르다. 경로 변환(#90) 전까지는 열지 않는다. */
    record EnvironmentMismatch(ProfileId profile, WorkspacePath path) implements OpenError {
        public EnvironmentMismatch {
            Objects.requireNonNull(profile, "profile");
            Objects.requireNonNull(path, "path");
        }
    }

    /** 지정한 프로파일이 탐지 목록에 없다. */
    record UnknownProfile(ProfileId profile) implements OpenError {
        public UnknownProfile {
            Objects.requireNonNull(profile, "profile");
        }
    }
}
