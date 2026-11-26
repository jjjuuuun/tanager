package dev.tanager.platform.macos;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tanager.application.port.out.Integration;
import dev.tanager.domain.history.ShellEnvironment;
import dev.tanager.domain.history.WorkspacePath;
import dev.tanager.domain.session.IntegrationMode;
import dev.tanager.domain.session.LaunchTemplate;
import dev.tanager.domain.session.ProfileId;
import dev.tanager.domain.session.ShellKind;
import dev.tanager.domain.session.ShellProfile;
import dev.tanager.domain.session.WorkingDir;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MacLaunchCommandTest {

    @Test
    @DisplayName("launchCommand 는 M7 전까지 UnsupportedOperationException")
    void launchCommandIsNotSupportedYet() {
        // TODO(M7): macOS 용 ShellEnvironment 가 아직 없어 Windows 환경으로 만든다. M7 에서 macOS 환경 · 실제 명령 테스트로 바꾼다
        ShellProfile zsh = new ShellProfile(
                new ProfileId("zsh"),
                "zsh",
                ShellKind.ZSH,
                new ShellEnvironment.Windows(),
                new LaunchTemplate("/bin/zsh", List.of()),
                IntegrationMode.OSC7);
        WorkingDir cwd = new WorkingDir.At(WorkspacePath.of(new ShellEnvironment.Windows(), "C:\\x")
                .value()
                .orElseThrow());

        assertThatThrownBy(() -> new MacPlatformModule().launchCommand(zsh, cwd, new Integration.Unavailable("M7 전")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("dirs 는 M7 전까지 UnsupportedOperationException")
    void dirsIsNotSupportedYet() {
        assertThatThrownBy(() -> new MacPlatformModule().dirs()).isInstanceOf(UnsupportedOperationException.class);
    }
}
