package tanager.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.session.IntegrationMode;
import tanager.domain.session.LaunchTemplate;
import tanager.domain.session.ProfileId;
import tanager.domain.session.ShellKind;
import tanager.domain.session.ShellProfile;
import tanager.domain.session.WorkingDir;

/** Fake 가 {@link PlatformModule#launchCommand} 계약을 지키는지 확인한다(다른 테스트가 Fake 를 믿어도 되는지). */
class FakePlatformModuleTest {
    private static final ShellProfile WSL = new ShellProfile(
            new ProfileId("wsl:A"),
            "A",
            ShellKind.BASH,
            new ShellEnvironment.Wsl("A"),
            new LaunchTemplate("wsl.exe", List.of()),
            IntegrationMode.NONE);

    @Test
    @DisplayName("launchCommand 는 프로파일과 다른 환경의 경로를 거부하고 기록하지 않는다")
    void launchCommandRejectsMismatchedEnvironment() {
        // given
        FakePlatformModule platform = new FakePlatformModule(List.of(WSL));
        WorkingDir windows = new WorkingDir.At(WorkspacePath.of(new ShellEnvironment.Windows(), "C:\\work")
                .value()
                .orElseThrow());

        // when / then
        assertThatThrownBy(() -> platform.launchCommand(WSL, windows, new Integration.Unavailable("x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(platform.launchRequests()).isEmpty();
    }
}
