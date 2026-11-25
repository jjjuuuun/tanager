package dev.tanager.platform.macos;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

class MacLaunchCommandTest {

    @Test
    @DisplayName("launchCommand 는 M7 전까지 UnsupportedOperationException")
    void launchCommandIsNotSupportedYet() {
        ShellProfile zsh = new ShellProfile(
                new ProfileId("zsh"),
                "zsh",
                ShellKind.ZSH,
                new ShellEnvironment.Windows(),
                new LaunchTemplate("/bin/zsh", List.of()),
                IntegrationMode.OSC7);
        WorkspacePath cwd = WorkspacePath.of(new ShellEnvironment.Windows(), "C:\\x")
                .value()
                .orElseThrow();

        assertThatThrownBy(() -> new MacPlatformModule().launchCommand(zsh, cwd))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
