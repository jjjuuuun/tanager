package dev.tanager.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tanager.domain.history.ShellEnvironment;
import dev.tanager.domain.session.IntegrationMode;
import dev.tanager.domain.session.LaunchTemplate;
import dev.tanager.domain.session.ProfileId;
import dev.tanager.domain.session.ShellKind;
import dev.tanager.domain.session.ShellProfile;
import dev.tanager.domain.session.WorkingDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** 실제 wsl.exe 로 {@code --cd ~} 가 홈으로 들어가는지 확인한다. WSL 배포판이 없으면 건너뛴다. */
@Tag("wsl")
@EnabledOnOs(OS.WINDOWS)
class WindowsWslHomeTest {
    private static final String WSL_EXE = "C:\\Windows\\System32\\wsl.exe";

    @Test
    @DisplayName("홈으로 띄운 WSL 셸의 pwd 는 $HOME 이다")
    void homeLaunchStartsInHomeDirectory() throws Exception {
        // given
        String distro = firstDistro();
        ShellProfile profile = new ShellProfile(
                new ProfileId("wsl:" + distro),
                distro,
                ShellKind.BASH,
                new ShellEnvironment.Wsl(distro),
                new LaunchTemplate(WSL_EXE, List.of("-d", distro)),
                IntegrationMode.OSC7);
        List<String> argv = new ArrayList<>(WindowsLaunchCommands.of(profile, new WorkingDir.Home(), "C:\\Users\\me")
                .argv());
        // 끝의 "bash -i" 를 한 줄 검사 스크립트로 바꿔 같은 --cd 인자를 그대로 쓴다
        argv.removeLast();
        argv.removeLast();
        argv.addAll(List.of("sh", "-c", "[ \"$(pwd)\" = \"$HOME\" ] && echo SAME_AS_HOME"));

        // when
        ProcessBuilder builder = new ProcessBuilder(argv).redirectErrorStream(true);
        builder.environment().put("WSL_UTF8", "1");
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // then
        assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
        assertThat(output).contains("SAME_AS_HOME");
    }

    private static String firstDistro() throws IOException, InterruptedException {
        Optional<String> fromEnv =
                Optional.ofNullable(System.getenv("TANAGER_TEST_WSL_DISTRO")).filter(name -> !name.isBlank());
        if (fromEnv.isPresent()) {
            return fromEnv.get();
        }
        ProcessBuilder builder = new ProcessBuilder(WSL_EXE, "--list", "--quiet").redirectErrorStream(true);
        builder.environment().put("WSL_UTF8", "1");
        Process process = builder.start();
        String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor(10, TimeUnit.SECONDS);
        Optional<String> found = text.replace("\u0000", "")
                .lines()
                .map(line -> line.replace("\uFEFF", "").trim())
                .filter(line -> !line.isEmpty() && !line.startsWith("docker-desktop"))
                .findFirst();
        Assumptions.assumeTrue(found.isPresent(), "설치된 WSL 배포판이 없다");
        return found.orElseThrow();
    }
}
