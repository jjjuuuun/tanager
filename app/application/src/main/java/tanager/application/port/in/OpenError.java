package tanager.application.port.in;

import java.util.Objects;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.VisitSource;
import tanager.domain.history.WorkspacePath;
import tanager.domain.session.ProfileId;
import tanager.domain.shared.PaneId;

/** 경로에서 창을 열 수 없는 이유(#88). */
public sealed interface OpenError {

    /** 그 경로의 환경을 띄울 셸이 탐지 목록에 없다(예: 이력에 남은 지운 WSL 배포판). */
    record NoProfileForEnvironment(ShellEnvironment env) implements OpenError {
        public NoProfileForEnvironment {
            Objects.requireNonNull(env, "env");
        }
    }

    /** 지정한 프로파일의 환경이 경로의 환경과 다르고, 그 환경의 경로로 바꿀 수도 없다(예: WSL 홈을 PowerShell 로, #90). */
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

    /** 그 창에 세션이 없다(이미 닫혔거나 셸이 아직 뜨지 않았다, #92). 끌어다 놓은 대상 창이 그사이 닫혀도 이 값이다(#151). */
    record UnknownPane(PaneId pane) implements OpenError {
        public UnknownPane {
            Objects.requireNonNull(pane, "pane");
        }
    }

    /** 그 출처의 대화 공급자가 등록돼 있지 않아 이어 할 명령을 모른다(#216). */
    record UnknownSource(VisitSource source) implements OpenError {
        public UnknownSource {
            Objects.requireNonNull(source, "source");
        }
    }
}
