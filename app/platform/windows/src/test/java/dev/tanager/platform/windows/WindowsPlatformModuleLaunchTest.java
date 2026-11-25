package dev.tanager.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tanager.domain.history.ShellEnvironment;
import dev.tanager.domain.history.WorkspacePath;
import dev.tanager.domain.session.IntegrationMode;
import dev.tanager.domain.session.LaunchTemplate;
import dev.tanager.domain.session.ProfileId;
import dev.tanager.domain.session.ShellKind;
import dev.tanager.domain.session.ShellProfile;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WindowsPlatformModuleLaunchTest {

    @Test
    @DisplayName("launchCommand 는 WindowsLaunchCommands 에 위임한다")
    void delegatesToWindowsLaunchCommands() {
        // given
        ShellProfile cmd = new ShellProfile(
                new ProfileId("cmd"),
                "cmd",
                ShellKind.CMD,
                new ShellEnvironment.Windows(),
                new LaunchTemplate("C:\\Windows\\System32\\cmd.exe", List.of()),
                IntegrationMode.NONE);
        WorkspacePath cwd = WorkspacePath.of(new ShellEnvironment.Windows(), "C:\\work")
                .value()
                .orElseThrow();

        // when
        var command = new WindowsPlatformModule().launchCommand(cmd, cwd);

        // then
        assertThat(command).isEqualTo(WindowsLaunchCommands.of(cmd, cwd));
        assertThat(command.argv()).containsExactly("C:\\Windows\\System32\\cmd.exe");
    }
}
