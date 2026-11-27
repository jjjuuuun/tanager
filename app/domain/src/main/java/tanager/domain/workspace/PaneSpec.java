package tanager.domain.workspace;

import java.util.Objects;
import java.util.Optional;
import tanager.domain.history.WorkspacePath;
import tanager.domain.session.ProfileId;
import tanager.domain.shared.PaneId;

/**
 * 창 하나에 어떤 셸을 어디서 띄울지(SDD §5.4). 셸은 UI 가 창을 그려 크기를 안 뒤 이 명세로 띄운다(#88).
 *
 * @param profile 띄울 프로파일. 비면 기억한 기본 셸(없으면 고르는 화면)
 * @param dir 시작 경로. 비면 그 프로파일의 설정 시작 경로 → 홈
 */
public record PaneSpec(PaneId pane, Optional<ProfileId> profile, Optional<WorkspacePath> dir) {
    public PaneSpec {
        Objects.requireNonNull(pane, "pane");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(dir, "dir");
    }

    /** 기억한 셸을 그 셸의 시작 경로에서(앱을 처음 열 때의 창). */
    public static PaneSpec remembered(PaneId pane) {
        return new PaneSpec(pane, Optional.empty(), Optional.empty());
    }
}
