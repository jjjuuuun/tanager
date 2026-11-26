package dev.tanager.ui.pane;

import dev.tanager.domain.session.SessionState;
import dev.tanager.domain.session.ShellProfile;
import dev.tanager.domain.session.TerminalSession;
import java.util.Objects;
import java.util.Optional;

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

    /** 기억한 셸로 새로 띄운다(없거나 사라졌으면 선택 화면이 된다). */
    record Fresh() implements Attempt {}

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
