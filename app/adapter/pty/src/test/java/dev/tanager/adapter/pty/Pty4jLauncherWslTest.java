package dev.tanager.adapter.pty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.tanager.application.port.out.LaunchCommand;
import dev.tanager.application.port.out.LaunchError;
import dev.tanager.application.port.out.OutputCollector;
import dev.tanager.application.port.out.TerminalSize;
import dev.tanager.application.port.out.TerminalStream;
import dev.tanager.domain.shared.Result;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** wsl.exe 의 --cd 로 작업 디렉터리를 넘겨 기동하는 통합 테스트(L1~L3). WSL 배포판이 없으면 건너뛴다. */
@Tag("wsl")
@EnabledOnOs(OS.WINDOWS)
class Pty4jLauncherWslTest {
    private static final Duration WAIT = Duration.ofSeconds(20);

    private final Pty4jLauncher launcher = new Pty4jLauncher();
    private final List<TerminalStream> opened = new ArrayList<>();
    private final OutputCollector out = new OutputCollector();
    private String distro;

    @BeforeEach
    void findDistro() throws Exception {
        String fromEnv = System.getenv("TANAGER_TEST_WSL_DISTRO");
        distro = fromEnv != null && !fromEnv.isBlank() ? fromEnv : firstInstalledDistro();
        Assumptions.assumeTrue(distro != null && !distro.isBlank(), "설치된 WSL 배포판이 없다");
    }

    @AfterEach
    void closeAll() {
        opened.forEach(TerminalStream::close);
    }

    private static String firstInstalledDistro() throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(WindowsShells.WSL, "--list", "--quiet");
        builder.environment().put("WSL_UTF8", "1");
        Process process = builder.redirectErrorStream(true).start();
        String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
            return null;
        }
        return text.replace("\u0000", "")
                .lines()
                .map(line -> line.replace("\uFEFF", "").trim())
                .filter(line -> !line.isEmpty())
                .findFirst()
                .orElse(null);
    }

    private TerminalStream launchBash(String cdPath, String script) {
        LaunchCommand command = new LaunchCommand(
                List.of(WindowsShells.WSL, "-d", distro, "--cd", cdPath, "--", "bash", "-c", script),
                Optional.empty(),
                Map.of());
        Result<TerminalStream, LaunchError> result = launcher.launch(command, TerminalSize.DEFAULT);
        assertThat(result.isOk()).as("기동 결과 %s", result).isTrue();
        TerminalStream stream = result.value().orElseThrow();
        opened.add(stream);
        stream.subscribe(out);
        stream.start();
        return stream;
    }

    @Test
    @DisplayName("L1 --cd /tmp 로 띄운 bash 의 pwd 는 /tmp 이고 종료 코드는 0")
    void startsInGivenDirectory() {
        TerminalStream stream = launchBash("/tmp", "pwd");

        assertThat(stream.onExit().toCompletableFuture()).succeedsWithin(WAIT).isEqualTo(0);
        assertThat(out.text()).contains("/tmp");
    }

    @Test
    @DisplayName("L2 공백 · 한글 · # 가 든 리눅스 경로도 그대로 전달된다")
    void unusualDirectoryNamesSurvive() throws Exception {
        // given
        String dir = "/tmp/한글 폴더 #1";
        Process mkdir = new ProcessBuilder(WindowsShells.WSL, "-d", distro, "--", "mkdir", "-p", dir)
                .redirectErrorStream(true)
                .start();
        mkdir.getInputStream().readAllBytes();
        Assumptions.assumeTrue(mkdir.waitFor(10, TimeUnit.SECONDS) && mkdir.exitValue() == 0, "테스트 폴더를 만들지 못했다");

        // when
        TerminalStream stream = launchBash(dir, "pwd");

        // then
        assertThat(stream.onExit().toCompletableFuture()).succeedsWithin(WAIT).isEqualTo(0);
        await().atMost(WAIT).until(() -> out.text().contains(dir));
    }

    @Test
    @DisplayName("L3 없는 리눅스 경로는 기동 오류가 아니라 0 이 아닌 종료 코드로 나타난다")
    void missingLinuxDirectoryShowsUpAsNonZeroExit() {
        TerminalStream stream = launchBash("/없는/경로", "pwd");

        assertThat(stream.onExit().toCompletableFuture()).succeedsWithin(WAIT).isNotEqualTo(0);
    }
}
