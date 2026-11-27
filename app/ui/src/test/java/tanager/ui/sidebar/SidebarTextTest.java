package tanager.ui.sidebar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.domain.history.Alias;
import tanager.domain.history.SessionLeaf;
import tanager.domain.history.SessionStatus;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.VisitedDirectory;
import tanager.domain.history.WorkspacePath;
import tanager.domain.shared.PaneId;

/** 사이드바 노드의 글자 · 툴팁(순수, #84). */
class SidebarTextTest {
    private static final ShellEnvironment UBUNTU = new ShellEnvironment.Wsl("Ubuntu-24.04");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private static WorkspacePath wsl(String raw) {
        return WorkspacePath.of(UBUNTU, raw).value().orElseThrow();
    }

    @Test
    @DisplayName("환경 노드는 'WSL · 배포판' 또는 'Windows' 다")
    void environmentLabels() {
        assertThat(SidebarText.environment(UBUNTU)).isEqualTo("WSL · Ubuntu-24.04");
        assertThat(SidebarText.environment(new ShellEnvironment.Windows())).isEqualTo("Windows");
    }

    @Test
    @DisplayName("세션은 '#번호 셸', 끝났으면 종료 코드를 붙인다")
    void sessionLabels() {
        SessionLeaf running =
                new SessionLeaf(new PaneId("p1"), 1, "bash", UBUNTU, Optional.empty(), new SessionStatus.Running());
        SessionLeaf exited =
                new SessionLeaf(new PaneId("p2"), 2, "bash", UBUNTU, Optional.empty(), new SessionStatus.Exited(3));

        assertThat(SidebarText.session(running)).isEqualTo("#1 bash");
        assertThat(SidebarText.session(exited)).isEqualTo("#2 bash · 종료 3");
    }

    @Test
    @DisplayName("방문한 폴더의 툴팁은 전체 경로 · 방문 횟수 · 최근 방문(현지 시각)이다")
    void folderTooltip() {
        // given
        VisitedDirectory visit = new VisitedDirectory(
                wsl("/home/me/work"),
                Instant.parse("2026-11-20T00:00:00Z"),
                Instant.parse("2026-11-27T00:30:00Z"),
                3,
                false,
                Optional.of(new Alias("작업")),
                false);

        // when
        String tooltip = SidebarText.folderTooltip(wsl("/home/me/work"), Optional.of(visit), SEOUL);

        // then
        assertThat(tooltip).isEqualTo("/home/me/work · 방문 3회 · 최근 2026-11-27 09:30");
    }

    @Test
    @DisplayName("방문 기록이 없는 폴더의 툴팁은 경로뿐이다")
    void unvisitedFolderTooltip() {
        assertThat(SidebarText.folderTooltip(wsl("/home"), Optional.empty(), SEOUL))
                .isEqualTo("/home");
    }

    @Test
    @DisplayName("사라진 경로의 툴팁은 끝에 '이 경로가 더 이상 없다' 를 붙인다(#186)")
    void missingFolderTooltip() {
        assertThat(SidebarText.folderTooltip(wsl("/home/old"), Optional.empty(), SEOUL, true))
                .isEqualTo("/home/old · 이 경로가 더 이상 없다");
        assertThat(SidebarText.folderTooltip(wsl("/home/old"), Optional.empty(), SEOUL, false))
                .isEqualTo("/home/old");
    }

    @Test
    @DisplayName("별칭은 보조 글자로 쓴다")
    void aliasIsSecondary() {
        assertThat(SidebarText.secondary(Optional.of(new Alias("작업")))).contains("작업");
        assertThat(SidebarText.secondary(Optional.empty())).isEmpty();
    }
}
