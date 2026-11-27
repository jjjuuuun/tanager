package tanager.platform.macos;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.out.Integration;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.session.IntegrationMode;
import tanager.domain.session.LaunchTemplate;
import tanager.domain.session.ProfileId;
import tanager.domain.session.ShellKind;
import tanager.domain.session.ShellProfile;
import tanager.domain.session.WorkingDir;

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

        assertThatThrownBy(() -> new MacPlatformModule()
                        .launchCommand(zsh, cwd, new Integration.Unavailable(new Integration.Problem.NotSupported())))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("dirs 는 M7 전까지 UnsupportedOperationException")
    void dirsIsNotSupportedYet() {
        assertThatThrownBy(() -> new MacPlatformModule().dirs()).isInstanceOf(UnsupportedOperationException.class);
    }
}
