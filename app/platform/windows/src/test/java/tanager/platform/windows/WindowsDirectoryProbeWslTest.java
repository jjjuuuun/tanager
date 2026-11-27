package tanager.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import tanager.application.port.out.DirectoryProbe;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;

/** 실제 배포판에서 {@code \\wsl.localhost} 로 확인한다(#53). WSL 배포판이 없으면 건너뛴다. VM 을 깨운다. */
@Tag("wsl")
@EnabledOnOs(OS.WINDOWS)
class WindowsDirectoryProbeWslTest {
    private static final String WSL_EXE = "C:\\Windows\\System32\\wsl.exe";
    // 첫 접근은 VM 기동을 기다려야 한다
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final DirectoryProbe probe = new WindowsPlatformModule().directories();

    @Test
    @DisplayName("실제 배포판의 홈은 true, 없는 경로는 false 다")
    void realHomeExistsAndMissingPathDoesNot() throws Exception {
        // given
        String distro = firstDistro();
        String home = homeOf(distro);
        ShellEnvironment env = new ShellEnvironment.Wsl(distro);
        WorkspacePath existing = WorkspacePath.of(env, home).value().orElseThrow();
        WorkspacePath missing = WorkspacePath.of(env, home + "/tanager-no-such-" + UUID.randomUUID())
                .value()
                .orElseThrow();

        // when
        Optional<Boolean> found = probe.isDirectory(existing, TIMEOUT);
        Optional<Boolean> gone = probe.isDirectory(missing, TIMEOUT);

        // then
        assertThat(found).contains(true);
        assertThat(gone).contains(false);
    }

    private static String homeOf(String distro) throws IOException, InterruptedException {
        // -e 는 셸 없이 실행한다 — $HOME 은 sh 가 펼친다. 리눅스 프로그램의 출력이라 UTF-8 이다
        Process process = new ProcessBuilder(WSL_EXE, "-d", distro, "-e", "sh", "-c", "printf %s \"$HOME\"")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        String home = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        process.waitFor(30, TimeUnit.SECONDS);
        Assumptions.assumeTrue(home.startsWith("/"), "배포판의 홈을 읽지 못했다");
        return home;
    }

    private static String firstDistro() throws IOException, InterruptedException {
        Optional<String> fromEnv =
                Optional.ofNullable(System.getenv("TANAGER_TEST_WSL_DISTRO")).filter(name -> !name.isBlank());
        if (fromEnv.isPresent()) {
            return fromEnv.get();
        }
        Process process = new ProcessBuilder(WSL_EXE, "--list", "--quiet")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        byte[] output = process.getInputStream().readAllBytes();
        process.waitFor(10, TimeUnit.SECONDS);
        Optional<String> found = WslListParser.parse(output).stream()
                .filter(name -> !name.startsWith("docker-desktop"))
                .findFirst();
        Assumptions.assumeTrue(found.isPresent(), "설치된 WSL 배포판이 없다");
        return found.orElseThrow();
    }
}
