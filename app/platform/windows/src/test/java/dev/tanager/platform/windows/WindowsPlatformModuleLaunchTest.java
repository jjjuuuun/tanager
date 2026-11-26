package dev.tanager.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tanager.domain.history.ShellEnvironment;
import dev.tanager.domain.history.WorkspacePath;
import dev.tanager.domain.session.IntegrationMode;
import dev.tanager.domain.session.LaunchTemplate;
import dev.tanager.domain.session.ProfileId;
import dev.tanager.domain.session.ShellKind;
import dev.tanager.domain.session.ShellProfile;
import dev.tanager.domain.session.WorkingDir;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WindowsPlatformModuleLaunchTest {

    private static final ShellProfile POWERSHELL = new ShellProfile(
            new ProfileId("powershell"),
            "Windows PowerShell",
            ShellKind.WINDOWS_POWERSHELL,
            new ShellEnvironment.Windows(),
            new LaunchTemplate("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe", List.of()),
            IntegrationMode.OSC9_9);

    private static WindowsPlatformModule module(Optional<String> userProfile) {
        WindowsShellCatalog catalog = new WindowsShellCatalog(
                new FakeCommandRunner(new CommandOutcome.NotStarted("x")),
                key -> Optional.empty(),
                Duration.ofSeconds(1));
        return new WindowsPlatformModule(catalog, key -> key.equals("USERPROFILE") ? userProfile : Optional.empty());
    }

    @Test
    @DisplayName("홈에서 띄우면 생성 때 읽은 USERPROFILE 이 작업 디렉터리가 된다")
    void homeResolvesToUserProfileFromEnv() {
        // when
        var command = module(Optional.of("D:\\사용자\\나")).launchCommand(POWERSHELL, new WorkingDir.Home());

        // then
        assertThat(command.workingDirectory()).contains("D:\\사용자\\나");
    }

    @Test
    @DisplayName("USERPROFILE 이 슬래시 경로면 역슬래시로 정규화하고, 절대 경로가 아니면 대체 홈을 쓴다 — 기동이 예외로 끝나지 않는다")
    void userProfileIsNormalizedOrFallsBack() {
        // given / when
        var slashed = module(Optional.of("C:/Users/me")).launchCommand(POWERSHELL, new WorkingDir.Home());
        WindowsShellCatalog catalog = new WindowsShellCatalog(
                new FakeCommandRunner(new CommandOutcome.NotStarted("x")),
                key -> Optional.empty(),
                Duration.ofSeconds(1));
        var relative = new WindowsPlatformModule(
                        catalog,
                        key -> key.equals("USERPROFILE") ? Optional.of("relative") : Optional.empty(),
                        "E:\\대체\\홈")
                .launchCommand(POWERSHELL, new WorkingDir.Home());
        // then
        assertThat(slashed.workingDirectory()).contains("C:\\Users\\me");
        assertThat(relative.workingDirectory()).contains("E:\\대체\\홈");
    }

    @Test
    @DisplayName("USERPROFILE 이 없거나 공백이면 주입한 대체 홈이 작업 디렉터리가 된다")
    void homeFallsBackToInjectedHomeWithoutUserProfile() {
        for (Optional<String> userProfile : List.of(Optional.<String>empty(), Optional.of("   "))) {
            // given
            WindowsShellCatalog catalog = new WindowsShellCatalog(
                    new FakeCommandRunner(new CommandOutcome.NotStarted("x")),
                    key -> Optional.empty(),
                    Duration.ofSeconds(1));
            WindowsPlatformModule module = new WindowsPlatformModule(
                    catalog, key -> key.equals("USERPROFILE") ? userProfile : Optional.empty(), "E:\\대체\\홈");

            // when
            var command = module.launchCommand(POWERSHELL, new WorkingDir.Home());

            // then
            assertThat(command.workingDirectory())
                    .as("USERPROFILE %s", userProfile)
                    .contains("E:\\대체\\홈");
        }
    }

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
        WorkingDir cwd = new WorkingDir.At(WorkspacePath.of(new ShellEnvironment.Windows(), "C:\\work")
                .value()
                .orElseThrow());

        // when
        var command = module(Optional.of("C:\\Users\\me")).launchCommand(cmd, cwd);

        // then
        assertThat(command).isEqualTo(WindowsLaunchCommands.of(cmd, cwd, "C:\\Users\\me"));
        assertThat(command.argv()).containsExactly("C:\\Windows\\System32\\cmd.exe");
    }
}
