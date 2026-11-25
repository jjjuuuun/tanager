package dev.tanager.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.tanager.application.port.in.StartNotice;
import dev.tanager.application.port.in.StartRequest;
import dev.tanager.application.port.in.StartedSession;
import dev.tanager.application.port.out.FakePlatformModule;
import dev.tanager.application.port.out.ScriptedPtyLauncher;
import dev.tanager.application.port.out.TerminalSize;
import dev.tanager.domain.history.ShellEnvironment;
import dev.tanager.domain.session.IntegrationMode;
import dev.tanager.domain.session.LaunchTemplate;
import dev.tanager.domain.session.ProfileId;
import dev.tanager.domain.session.ShellKind;
import dev.tanager.domain.session.ShellProfile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssemblyTest {
    private static final ShellProfile CMD = new ShellProfile(
            new ProfileId("cmd"),
            "cmd",
            ShellKind.CMD,
            new ShellEnvironment.Windows(),
            new LaunchTemplate("cmd.exe", List.of()),
            IntegrationMode.NONE);

    @TempDir
    Path appData;

    @Test
    @DisplayName("조립하면 셸 탐지가 미리 한 번 돌고, 설정의 기본 프로파일이 없으면 대체했다는 알림과 함께 시작한다")
    void assemblePreDetectsShellsAndStartsWithConfigFromAppData() throws Exception {
        // given
        Files.writeString(appData.resolve("config.json"), "{ \"defaultProfile\": \"ghost\" }", StandardCharsets.UTF_8);
        FakePlatformModule platform = new FakePlatformModule(List.of(CMD)).withAppData(appData);
        ScriptedPtyLauncher launcher = new ScriptedPtyLauncher();

        // when
        Assembly assembly = Assembly.assemble(platform, launcher);

        // then 셸 탐지는 start 를 부르기 전에 미리 시작된다
        await().atMost(Duration.ofSeconds(2)).until(() -> platform.shellsCalls() == 1);

        // when
        StartedSession started = assembly.sessions()
                .start(new StartRequest(Optional.empty(), Optional.empty(), new TerminalSize(80, 24)))
                .value()
                .orElseThrow();

        // then
        assertThat(started.notices()).contains(new StartNotice.ProfileReplaced(new ProfileId("ghost"), CMD.id()));
        assertThat(platform.shellsCalls()).isEqualTo(1);
        assertThat(launcher.commands()).hasSize(1);
        started.stream().close();
    }
}
