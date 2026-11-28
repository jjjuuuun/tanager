package tanager.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import tanager.application.port.out.HomeError;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;

/** 실제 wsl.exe 로 배포판의 홈을 묻고 그 호스트 경로가 열리는지 확인한다(#205). WSL 배포판이 없으면 건너뛴다. */
@Tag("wsl")
@EnabledOnOs(OS.WINDOWS)
class WindowsHostFilesWslTest {

    @Test
    @DisplayName("실제 배포판의 홈은 /home/ 또는 /root 이고 그 호스트 경로는 디렉터리로 있다")
    void realDistroHomeIsReadableFromHost() {
        // given
        ShellEnvironment env = new ShellEnvironment.Wsl(firstDistro());
        WindowsHostFiles files =
                new WindowsHostFiles(new WslMountRoots(), new ProcessCommandRunner(), System.getProperty("user.home"));

        // when
        var home = files.home(env);

        // then
        assertThat(home.value()).as("홈: %s", home).isPresent();
        String path = home.value().map(WorkspacePath::path).orElseThrow();
        assertThat(path.startsWith("/home/") || path.equals("/root")).as(path).isTrue();
        Optional<Path> host = files.hostPath(home.value().orElseThrow());
        assertThat(host)
                .hasValueSatisfying(
                        dir -> assertThat(Files.isDirectory(dir)).as("%s", dir).isTrue());
    }

    @Test
    @DisplayName("없는 배포판은 NotRunnable 이다")
    void missingDistroIsNotRunnable() {
        // given
        ShellEnvironment env = new ShellEnvironment.Wsl("TanagerNoSuchDistro");
        WindowsHostFiles files =
                new WindowsHostFiles(new WslMountRoots(), new ProcessCommandRunner(), System.getProperty("user.home"));

        // when
        var home = files.home(env);

        // then
        assertThat(home.error()).contains(new HomeError.NotRunnable(env));
    }

    private static String firstDistro() {
        Optional<String> fromEnv =
                Optional.ofNullable(System.getenv("TANAGER_TEST_WSL_DISTRO")).filter(name -> !name.isBlank());
        if (fromEnv.isPresent()) {
            return fromEnv.get();
        }
        CommandOutcome listed = new ProcessCommandRunner()
                .run(List.of("wsl.exe", "--list", "--quiet"), Map.of("WSL_UTF8", "1"), Duration.ofSeconds(10));
        Optional<String> found = switch (listed) {
            case CommandOutcome.Exited(int code, byte[] stdout)
            when code == 0 ->
                new String(stdout, StandardCharsets.UTF_8)
                        .replace("\u0000", "")
                        .lines()
                        .map(line -> line.replace("﻿", "").trim())
                        .filter(line -> !line.isEmpty() && !line.startsWith("docker-desktop"))
                        .findFirst();
            default -> Optional.empty();
        };
        Assumptions.assumeTrue(found.isPresent(), "설치된 WSL 배포판이 없다");
        return found.orElseThrow();
    }
}
