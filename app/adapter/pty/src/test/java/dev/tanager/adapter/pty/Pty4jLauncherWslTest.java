package dev.tanager.adapter.pty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.tanager.application.port.out.LaunchCommand;
import dev.tanager.application.port.out.LaunchCommand.EnvNames;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    void findDistro() {
        Optional<String> fromEnv =
                Optional.ofNullable(System.getenv("TANAGER_TEST_WSL_DISTRO")).filter(name -> !name.isBlank());
        Optional<String> found = fromEnv.or(Pty4jLauncherWslTest::firstInstalledDistro);
        Assumptions.assumeTrue(found.isPresent(), "설치된 WSL 배포판이 없다");
        distro = found.orElseThrow();
    }

    @AfterEach
    void closeAll() {
        opened.forEach(TerminalStream::close);
        opened.forEach(stream -> await().atMost(WAIT)
                .until(() -> stream.onExit().toCompletableFuture().isDone()));
    }

    // docker-desktop* 는 Docker 가 쓰는 내부 배포판이라 bash 셸 테스트 대상이 아니다
    private static Optional<String> firstInstalledDistro() {
        return run(List.of(WindowsShells.WSL, "--list", "--quiet"))
                .filter(outcome -> outcome.exitCode() == 0)
                .flatMap(outcome -> outcome.text()
                        .replace("\u0000", "")
                        .lines()
                        .map(line -> line.replace("\uFEFF", "").trim())
                        .filter(line -> !line.isEmpty() && !line.startsWith("docker-desktop"))
                        .findFirst());
    }

    private record Outcome(int exitCode, String text) {}

    /** 짧은 보조 명령을 실행한다. 출력은 따로 읽어 파이프가 차서 막히지 않게 하고, 10초 안에 안 끝나면 포기한다. */
    private static Optional<Outcome> run(List<String> command) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            builder.environment().put("WSL_UTF8", "1");
            Process process = builder.start();
            CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> {
                try {
                    return process.getInputStream().readAllBytes();
                } catch (IOException e) {
                    return new byte[0];
                }
            });
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            String text = new String(output.get(10, TimeUnit.SECONDS), StandardCharsets.UTF_8);
            return Optional.of(new Outcome(process.exitValue(), text));
        } catch (IOException | ExecutionException | TimeoutException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private TerminalStream launchBash(String cdPath, String script) {
        LaunchCommand command = new LaunchCommand(
                List.of(WindowsShells.WSL, "-d", distro, "--cd", cdPath, "--", "bash", "-c", script),
                Optional.empty(),
                Map.of(),
                EnvNames.CASE_INSENSITIVE);
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
    void unusualDirectoryNamesSurvive() {
        // given
        String dir = "/tmp/한글 폴더 #1";
        boolean created = run(List.of(WindowsShells.WSL, "-d", distro, "--", "mkdir", "-p", dir))
                .filter(outcome -> outcome.exitCode() == 0)
                .isPresent();
        Assumptions.assumeTrue(created, "테스트 폴더를 만들지 못했다");

        // when
        TerminalStream stream = launchBash(dir, "pwd");

        // then
        assertThat(stream.onExit().toCompletableFuture()).succeedsWithin(WAIT).isEqualTo(0);
        await().atMost(WAIT).until(() -> out.text().contains(dir));
    }

    @Test
    @DisplayName("L3 없는 리눅스 경로는 기동 오류가 아니다 — wsl.exe 가 chdir 실패를 알리고 / 에서 실행한다(종료 코드 0)")
    void missingLinuxDirectoryFallsBackToRoot() {
        // given / when
        TerminalStream stream = launchBash("/없는/경로", "pwd");

        // then: wsl.exe 2.5 의 동작(출력에 "chdir(…) failed", 종료 코드 0). 미리 확인하는 일은 복원(M5)이 맡는다
        assertThat(stream.onExit().toCompletableFuture()).succeedsWithin(WAIT).isEqualTo(0);
        assertThat(out.text()).contains("chdir(");
    }
}
