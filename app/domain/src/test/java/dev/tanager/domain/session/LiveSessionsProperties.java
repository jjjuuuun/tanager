package dev.tanager.domain.session;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tanager.domain.history.ShellEnvironment;
import dev.tanager.domain.history.WorkspacePath;
import dev.tanager.domain.shared.PaneId;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class LiveSessionsProperties {
    private static final ShellEnvironment UBUNTU = new ShellEnvironment.Wsl("Ubuntu");
    private static final PaneId PANE = new PaneId("p1");
    private static final List<WorkspacePath> PATHS = List.of(path("/a"), path("/b"), path("/c"));

    private static WorkspacePath path(String raw) {
        return WorkspacePath.of(UBUNTU, raw).value().orElseThrow();
    }

    @Property
    @Label("임의의 보고열에서 변화로 돌려주는 횟수는 연속으로 달라지는 횟수와 같고 마지막 경로가 기억된다")
    void changesEqualRunsOfDistinctPaths(@ForAll("reports") List<Integer> picks) {
        // given
        ShellProfile profile = new ShellProfile(
                new ProfileId("wsl:Ubuntu"),
                "Ubuntu",
                ShellKind.BASH,
                UBUNTU,
                new LaunchTemplate("wsl.exe", List.of()),
                IntegrationMode.OSC7);
        TerminalSession running = TerminalSession.start(profile, new WorkingDir.Home())
                .value()
                .orElseThrow()
                .launched(1)
                .value()
                .orElseThrow();
        LiveSessions sessions = LiveSessions.empty().launched(PANE, running);

        // when
        int changes = 0;
        Integer previous = null;
        for (Integer pick : picks) {
            var next = sessions.cwdReported(PANE, PATHS.get(pick));
            boolean expectedChange = !pick.equals(previous);
            assertThat(next.isPresent()).isEqualTo(expectedChange);
            if (next.isPresent()) {
                sessions = next.get();
                changes++;
            }
            previous = pick;
        }

        // then
        assertThat(sessions.byPane().get(PANE).lastReported())
                .isEqualTo(picks.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(PATHS.get(previous)));
        assertThat(changes).isLessThanOrEqualTo(picks.size());
    }

    @Provide
    Arbitrary<List<Integer>> reports() {
        return Arbitraries.integers().between(0, PATHS.size() - 1).list().ofMaxSize(30);
    }
}
