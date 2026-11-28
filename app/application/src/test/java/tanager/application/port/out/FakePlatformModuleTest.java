package tanager.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
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
        assertThatThrownBy(() -> platform.launchCommand(
                        WSL, windows, new Integration.Unavailable(new Integration.Problem.NotSupported())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(platform.launchRequests()).isEmpty();
    }

    @Test
    @DisplayName("appearance 는 기본으로 테마를 모르고 애니메이션을 켜며 withAppearance 로 바꿀 수 있다")
    void appearanceDefaultsToAnimationsOn() {
        // given
        FakePlatformModule platform = new FakePlatformModule(List.of(WSL));

        // when
        SystemAppearance initial = platform.appearance();
        SystemAppearance changed = platform.withAppearance(new SystemAppearance(Optional.of(Theme.LIGHT), false))
                .appearance();

        // then
        assertThat(initial).isEqualTo(SystemAppearance.DEFAULT);
        assertThat(changed.theme()).contains(Theme.LIGHT);
        assertThat(changed.animations()).isFalse();
        assertThat(platform.appearanceCalls()).isEqualTo(2);
        assertThat(platform.maxConcurrentAppearanceCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("hostFiles 는 기본으로 홈을 모르고 withHostFiles 로 바꿀 수 있다(#205)")
    void hostFilesDefaultToUnknownHome() {
        // given
        FakePlatformModule platform = new FakePlatformModule(List.of(WSL));
        ShellEnvironment env = new ShellEnvironment.Wsl("A");
        WorkspacePath home = WorkspacePath.of(env, "/home/a").value().orElseThrow();
        FakeHostFiles files = new FakeHostFiles().withHome(env, home);

        // when
        var before = platform.hostFiles().home(env);
        var after = platform.withHostFiles(files).hostFiles().home(env);

        // then
        assertThat(before.error()).contains(new HomeError.NotRunnable(env));
        assertThat(after.value()).contains(home);
        assertThat(files.homeRequests()).containsExactly(env);
    }

    @Test
    @DisplayName("directories 는 기본으로 모든 경로가 있다고 답하고 withDirectories 로 바꿀 수 있다")
    void directoriesDefaultToExisting() {
        // given
        FakePlatformModule platform = new FakePlatformModule(List.of(WSL));
        WorkspacePath gone =
                WorkspacePath.of(new ShellEnvironment.Wsl("A"), "/gone").value().orElseThrow();
        FakeDirectoryProbe probe = new FakeDirectoryProbe().missing(gone);

        // when
        var before = platform.directories().isDirectory(gone, Duration.ofSeconds(1));
        var after = platform.withDirectories(probe).directories().isDirectory(gone, Duration.ofSeconds(1));

        // then
        assertThat(before).contains(true);
        assertThat(after).contains(false);
        assertThat(probe.calls()).hasSize(1);
    }

    @Test
    @DisplayName("appearance 는 failAppearanceWith 로 던지게 할 수 있고 빈 값으로 되돌린다")
    void appearanceCanFail() {
        // given
        FakePlatformModule platform = new FakePlatformModule(List.of(WSL))
                .failAppearanceWith(Optional.of(new IllegalStateException("계약 위반")));

        // when / then
        assertThatThrownBy(platform::appearance).isInstanceOf(IllegalStateException.class);
        assertThat(platform.failAppearanceWith(Optional.empty()).appearance()).isEqualTo(SystemAppearance.DEFAULT);
        assertThat(platform.appearanceCalls()).isEqualTo(2);
    }

    @Test
    @DisplayName("SystemAppearance.DEFAULT 는 테마를 모르고 애니메이션을 켠다(OS 값을 못 읽었을 때)")
    void defaultAppearanceEnablesAnimations() {
        assertThat(SystemAppearance.DEFAULT.theme()).isEmpty();
        assertThat(SystemAppearance.DEFAULT.animations()).isTrue();
    }
}
