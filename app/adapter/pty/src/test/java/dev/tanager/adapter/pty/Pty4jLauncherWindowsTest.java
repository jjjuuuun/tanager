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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** ConPTY 위에서 cmd · PowerShell 5.1 을 실제로 띄우는 통합 테스트(W1~W9). */
@Tag("windows")
@EnabledOnOs(OS.WINDOWS)
class Pty4jLauncherWindowsTest {
    private static final Duration WAIT = Duration.ofSeconds(10);

    private final Pty4jLauncher launcher = new Pty4jLauncher();
    private final List<TerminalStream> opened = new ArrayList<>();
    private final OutputCollector out = new OutputCollector();

    @AfterEach
    void closeAll() {
        // close 는 바로 돌아온다 — 셸이 끝나기 전에 @TempDir 를 지우면 "사용 중" 으로 실패한다(W9)
        opened.forEach(TerminalStream::close);
        opened.forEach(stream -> await().atMost(WAIT)
                .until(() -> stream.onExit().toCompletableFuture().isDone()));
    }

    private TerminalStream start(LaunchCommand command, TerminalSize size) {
        Result<TerminalStream, LaunchError> result = launcher.launch(command, size);
        assertThat(result.isOk()).as("기동 결과 %s", result).isTrue();
        TerminalStream stream = result.value().orElseThrow();
        opened.add(stream);
        stream.subscribe(out);
        stream.start();
        return stream;
    }

    private static int count(String haystack, String needle) {
        return haystack.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    @Test
    @DisplayName("W1 cmd 에 명령을 쓰면 그 결과가 출력에 나온다")
    void cmdEchoesCommandResult() {
        TerminalStream stream = start(WindowsShells.cmd(), TerminalSize.DEFAULT);

        stream.write(WindowsShells.line("echo W1MARK"));

        // 입력 에코 한 번 + 명령 결과 한 번
        await().atMost(WAIT).until(() -> count(out.text(), "W1MARK") >= 2);
    }

    @Test
    @DisplayName("W2 cmd 에서 exit 3 을 치면 onExit 가 3 으로 완료된다")
    void cmdExitCodeIsReported() {
        TerminalStream stream = start(WindowsShells.cmd(), TerminalSize.DEFAULT);

        stream.write(WindowsShells.line("exit 3"));

        assertThat(stream.onExit().toCompletableFuture()).succeedsWithin(WAIT).isEqualTo(3);
    }

    @Test
    @DisplayName("W3 초기 크기 90x25 가 PowerShell 창 크기로 보인다")
    void initialSizeIsApplied() {
        TerminalStream stream = start(WindowsShells.powershell(), new TerminalSize(90, 25));

        stream.write(
                WindowsShells.line("\"{0}x{1}\" -f $Host.UI.RawUI.WindowSize.Width,$Host.UI.RawUI.WindowSize.Height"));

        await().atMost(WAIT).until(() -> out.text().contains("90x25"));
    }

    @Test
    @DisplayName("W4 resize(100,30) 뒤에는 PowerShell 창 크기가 100x30 으로 보인다")
    void resizeIsApplied() {
        TerminalStream stream = start(WindowsShells.powershell(), new TerminalSize(90, 25));
        String ask = "\"{0}x{1}\" -f $Host.UI.RawUI.WindowSize.Width,$Host.UI.RawUI.WindowSize.Height";
        stream.write(WindowsShells.line(ask));
        await().atMost(WAIT).until(() -> out.text().contains("90x25"));

        stream.resize(new TerminalSize(100, 30));
        stream.write(WindowsShells.line(ask));

        await().atMost(WAIT).until(() -> out.text().contains("100x30"));
    }

    @Test
    @DisplayName("W5 없는 작업 디렉터리는 WorkingDirectoryNotFound 로 실패한다")
    void missingWorkingDirectory(@TempDir Path temp) {
        String missing = temp.resolve("없는 폴더").toString();
        LaunchCommand command = new LaunchCommand(List.of(WindowsShells.cmdExe()), Optional.of(missing), Map.of());

        Result<TerminalStream, LaunchError> result = launcher.launch(command, TerminalSize.DEFAULT);

        assertThat(result.error()).contains(new LaunchError.WorkingDirectoryNotFound(missing));
    }

    @Test
    @DisplayName("W6 없는 실행 파일은 ExecutableNotFound 로 실패한다")
    void missingExecutable() {
        String missing = "C:\\없는폴더\\nope.exe";
        LaunchCommand command = new LaunchCommand(List.of(missing), Optional.empty(), Map.of());

        Result<TerminalStream, LaunchError> result = launcher.launch(command, TerminalSize.DEFAULT);

        assertThat(result.error()).contains(new LaunchError.ExecutableNotFound(missing));
    }

    @Test
    @DisplayName("W7 close 하면 실행 중이던 자손 프로세스까지 모두 죽는다")
    void closeKillsTheWholeProcessTree() {
        // given
        TerminalStream stream = start(WindowsShells.cmd(), TerminalSize.DEFAULT);
        stream.write(WindowsShells.line("ping -n 600 127.0.0.1"));
        ProcessHandle root = ProcessHandle.of(stream.pid()).orElseThrow();
        await().atMost(WAIT).until(() -> root.descendants().findAny().isPresent());
        List<ProcessHandle> tree = new ArrayList<>(root.descendants().toList());
        tree.add(root);

        // when
        stream.close();

        // then
        await().atMost(WAIT).until(() -> tree.stream().noneMatch(ProcessHandle::isAlive));
    }

    @Test
    @DisplayName("W8 한글 출력이 UTF-8 로 온다(코드 페이지 그대로)")
    void koreanOutputIsUtf8() {
        TerminalStream stream = start(WindowsShells.cmd(), TerminalSize.DEFAULT);

        stream.write(WindowsShells.line("echo 한글ABC"));

        await().atMost(WAIT).until(() -> count(out.text(), "한글ABC") >= 2);
    }

    @Test
    @DisplayName("W9 공백 · 한글 · # 가 든 작업 디렉터리와 환경 변수가 적용된다")
    void workingDirectoryAndEnvironmentAreApplied(@TempDir Path temp) throws IOException {
        // given
        Path dir = Files.createDirectory(temp.resolve("한글 폴더 #1"));
        LaunchCommand command = new LaunchCommand(
                List.of(WindowsShells.cmdExe()), Optional.of(dir.toString()), Map.of("TANAGER_T", "1"));
        // 긴 임시 경로가 80열에서 줄바꿈되지 않게 넓게 띄운다
        TerminalStream stream = start(command, new TerminalSize(300, 30));

        // when
        stream.write(WindowsShells.line("cd"));
        stream.write(WindowsShells.line("echo %TERM% %TANAGER_T%"));

        // then
        // 임시 폴더 앞부분은 러너마다 8.3 짧은 이름(RUNNER~1)일 수 있어 우리가 만든 폴더 이름으로 확인한다
        String expectedDir = "\\" + dir.getFileName();
        await().atMost(WAIT).until(() -> out.text().contains(expectedDir));
        await().atMost(WAIT).until(() -> out.text().contains("xterm-256color 1"));
    }
}
