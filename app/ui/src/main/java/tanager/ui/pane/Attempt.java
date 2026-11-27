package tanager.ui.pane;

import java.util.Objects;
import java.util.Optional;
import tanager.domain.history.WorkspacePath;
import tanager.domain.session.ProfileId;
import tanager.domain.session.SessionState;
import tanager.domain.session.ShellProfile;
import tanager.domain.session.TerminalSession;

/** 셸을 띄우는 한 번의 시도. 실패하면 같은 시도로 다시 띄운다. */
sealed interface Attempt {

    /** 띄울 프로파일. 기억한 셸로 띄우는 시도는 아직 모른다. */
    default Optional<ShellProfile> knownProfile() {
        return switch (this) {
            case Fresh _ -> Optional.empty();
            case Restart(TerminalSession exited) -> Optional.of(exited.profile());
            case Chosen(ShellProfile profile, boolean _) -> Optional.of(profile);
        };
    }

    /**
     * 창의 명세대로 새로 띄운다. 프로파일이 비면 기억한 셸(없거나 사라졌으면 선택 화면), 경로가 비면 그 셸의 시작 경로.
     *
     * @param profile 띄울 프로파일(트리에서 연 창, #89)
     * @param dir 시작 경로(트리에서 연 창의 경로)
     */
    record Fresh(Optional<ProfileId> profile, Optional<WorkspacePath> dir) implements Attempt {
        public Fresh {
            Objects.requireNonNull(profile, "profile");
            Objects.requireNonNull(dir, "dir");
        }

        /** 기억한 셸을 그 셸의 시작 경로에서. */
        Fresh() {
            this(Optional.empty(), Optional.empty());
        }
    }

    /** 끝난 세션을 같은 프로파일로 마지막 경로에서 다시 띄운다. */
    record Restart(TerminalSession exited) implements Attempt {
        public Restart {
            Objects.requireNonNull(exited, "exited");
            if (!(exited.state() instanceof SessionState.Exited)) {
                throw new IllegalArgumentException("다시 띄울 세션은 Exited 여야 한다: " + exited.state());
            }
        }
    }

    /** 선택 화면에서 고른 셸을 그 셸의 시작 경로에서 새로 띄운다. {@code remember} 면 성공 뒤 기본으로 저장한다. */
    record Chosen(ShellProfile profile, boolean remember) implements Attempt {
        public Chosen {
            Objects.requireNonNull(profile, "profile");
        }
    }
}
