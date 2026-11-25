package dev.tanager.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tanager.application.port.out.LaunchCommand;
import dev.tanager.domain.history.ShellEnvironment;
import dev.tanager.domain.history.WorkspacePath;
import dev.tanager.domain.session.IntegrationMode;
import dev.tanager.domain.session.LaunchTemplate;
import dev.tanager.domain.session.ProfileId;
import dev.tanager.domain.session.ShellKind;
import dev.tanager.domain.session.ShellProfile;
import dev.tanager.domain.session.WorkingDir;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WindowsLaunchCommandsTest {
    private static final String WSL_EXE = "C:\\Windows\\System32\\wsl.exe";
    private static final String PS_EXE = "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe";
    private static final String CMD_EXE = "C:\\Windows\\System32\\cmd.exe";

    private static final ShellProfile WSL = new ShellProfile(
            new ProfileId("wsl:Ubuntu"),
            "Ubuntu",
            ShellKind.BASH,
            new ShellEnvironment.Wsl("Ubuntu"),
            new LaunchTemplate(WSL_EXE, List.of("-d", "Ubuntu")),
            IntegrationMode.OSC7);
    private static final ShellProfile POWERSHELL = new ShellProfile(
            new ProfileId("powershell"),
            "Windows PowerShell",
            ShellKind.WINDOWS_POWERSHELL,
            new ShellEnvironment.Windows(),
            new LaunchTemplate(PS_EXE, List.of("-NoLogo")),
            IntegrationMode.OSC9_9);
    private static final ShellProfile CMD = new ShellProfile(
            new ProfileId("cmd"),
            "cmd",
            ShellKind.CMD,
            new ShellEnvironment.Windows(),
            new LaunchTemplate(CMD_EXE, List.of()),
            IntegrationMode.NONE);

    private static final String USER_PROFILE = "C:\\Users\\me";

    private static WorkingDir path(ShellEnvironment env, String raw) {
        return new WorkingDir.At(WorkspacePath.of(env, raw).value().orElseThrow());
    }

    private static LaunchCommand of(ShellProfile profile, WorkingDir dir) {
        return WindowsLaunchCommands.of(profile, dir, USER_PROFILE);
    }

    @Test
    @DisplayName("P1 WSL 은 wsl.exe -d 배포판 --cd 경로 -- bash -i 이고 작업 디렉터리는 비운다")
    void wslUsesCdOption() {
        LaunchCommand command = of(WSL, path(new ShellEnvironment.Wsl("Ubuntu"), "/home/me/p"));

        assertThat(command.argv()).containsExactly(WSL_EXE, "-d", "Ubuntu", "--cd", "/home/me/p", "--", "bash", "-i");
        assertThat(command.workingDirectory()).isEmpty();
        assertThat(command.environment()).isEmpty();
    }

    @Test
    @DisplayName("P2 PowerShell 은 템플릿 그대로 띄우고 작업 디렉터리를 Windows 경로로 준다")
    void powershellUsesWorkingDirectory() {
        LaunchCommand command = of(POWERSHELL, path(new ShellEnvironment.Windows(), "C:\\work"));

        assertThat(command.argv()).containsExactly(PS_EXE, "-NoLogo");
        assertThat(command.workingDirectory()).contains("C:\\work");
        assertThat(command.environment()).isEmpty();
    }

    @Test
    @DisplayName("P3 cmd 는 실행 파일만으로 띄우고 작업 디렉터리를 Windows 경로로 준다")
    void cmdUsesWorkingDirectory() {
        LaunchCommand command = of(CMD, path(new ShellEnvironment.Windows(), "C:\\work"));

        assertThat(command.argv()).containsExactly(CMD_EXE);
        assertThat(command.workingDirectory()).contains("C:\\work");
    }

    @Test
    @DisplayName("P4 공백 · 한글이 든 경로도 argv 원소 하나로 유지한다")
    void unusualPathsStayOneArgument() {
        WorkingDir linux = path(new ShellEnvironment.Wsl("Ubuntu"), "/home/me/한글 폴더 #1");
        WorkingDir windows = path(new ShellEnvironment.Windows(), "C:\\한글 폴더 #1");

        assertThat(of(WSL, linux).argv()).contains("/home/me/한글 폴더 #1");
        assertThat(of(CMD, windows).workingDirectory()).contains("C:\\한글 폴더 #1");
    }

    @Test
    @DisplayName("P5 프로파일과 경로의 환경이 다르면 거부한다")
    void mismatchedEnvironmentIsRejected() {
        WorkingDir windows = path(new ShellEnvironment.Windows(), "C:\\work");
        WorkingDir otherDistro = path(new ShellEnvironment.Wsl("Debian"), "/home/me");

        assertThatThrownBy(() -> of(WSL, windows)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of(WSL, otherDistro)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of(POWERSHELL, otherDistro)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("P6 WSL 의 홈은 --cd ~ 로 넘기고 작업 디렉터리는 비운다")
    void wslHomeUsesTilde() {
        LaunchCommand command = of(WSL, new WorkingDir.Home());

        assertThat(command.argv()).containsExactly(WSL_EXE, "-d", "Ubuntu", "--cd", "~", "--", "bash", "-i");
        assertThat(command.workingDirectory()).isEmpty();
    }

    @Test
    @DisplayName("P7 PowerShell 의 홈은 USERPROFILE 을 작업 디렉터리로 준다")
    void powershellHomeUsesUserProfile() {
        LaunchCommand command = of(POWERSHELL, new WorkingDir.Home());

        assertThat(command.argv()).containsExactly(PS_EXE, "-NoLogo");
        assertThat(command.workingDirectory()).contains(USER_PROFILE);
    }

    @Test
    @DisplayName("P8 cmd 의 홈도 USERPROFILE 을 작업 디렉터리로 준다")
    void cmdHomeUsesUserProfile() {
        LaunchCommand command = of(CMD, new WorkingDir.Home());

        assertThat(command.argv()).containsExactly(CMD_EXE);
        assertThat(command.workingDirectory()).contains(USER_PROFILE);
    }

    @Provide
    Arbitrary<String> segments() {
        return Arbitraries.strings()
                .withChars(' ', '#', '%', '&', '한', '글', 'a', 'Z', '1', '_', '-')
                .ofMinLength(1)
                .ofMaxLength(20);
    }

    @Property
    @Label("어떤 경로든 정규화된 경로 문자열이 argv 원소 하나 또는 작업 디렉터리 그대로 쓰인다")
    void pathIsNeverSplitOrAltered(@ForAll("segments") String segment) {
        var linux = WorkspacePath.of(new ShellEnvironment.Wsl("Ubuntu"), "/home/" + segment);
        var windows = WorkspacePath.of(new ShellEnvironment.Windows(), "C:\\" + segment);
        Assume.that(linux.isOk() && windows.isOk());

        LaunchCommand wsl = of(WSL, new WorkingDir.At(linux.value().orElseThrow()));
        LaunchCommand cmd = of(CMD, new WorkingDir.At(windows.value().orElseThrow()));

        assertThat(wsl.argv().stream()
                        .filter(a -> a.equals(linux.value().orElseThrow().path()))
                        .count())
                .isEqualTo(1);
        assertThat(wsl.argv()).hasSize(8);
        assertThat(cmd.workingDirectory())
                .isEqualTo(Optional.of(windows.value().orElseThrow().path()));
        assertThat(cmd.argv()).containsExactly(CMD_EXE);
        assertThat(cmd.environment()).isEqualTo(Map.of());
    }
}
