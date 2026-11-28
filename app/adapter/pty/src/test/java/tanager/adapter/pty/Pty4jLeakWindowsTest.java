package tanager.adapter.pty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import tanager.application.port.out.LaunchCommand;
import tanager.application.port.out.TerminalSize;
import tanager.application.port.out.TerminalStream;

/**
 * 실제 ConPTY 로 cmd · PowerShell 5.1 을 30번씩 띄우고 닫아도 자손 프로세스(셸 · OpenConsole · conhost)와 PTY 읽기 스레드가 남지
 * 않는다(#223, ADR-0007).
 */
@Tag("windows")
@EnabledOnOs(OS.WINDOWS)
class Pty4jLeakWindowsTest {
    private static final int ROUNDS = 30;
    private static final Duration FIRST_OUTPUT = Duration.ofSeconds(20);
    // 어댑터는 close 뒤 3초 유예하고 남은 자손을 강제 종료한다 — 그보다 넉넉히 기다린다
    private static final Duration CLEANUP = Duration.ofSeconds(30);
    private static final String READER_THREAD = "pty-read-";

    private final Pty4jLauncher launcher = new Pty4jLauncher();

    @Test
    @DisplayName("cmd 를 30번 띄우고 닫으면 새 자손 프로세스와 pty-read 스레드가 0 이다")
    void cmdRepeatedly() {
        repeat(WindowsShells.cmd());
    }

    @Test
    @DisplayName("PowerShell 5.1 을 30번 띄우고 닫으면 새 자손 프로세스와 pty-read 스레드가 0 이다")
    void powershellRepeatedly() {
        repeat(WindowsShells.powershell());
    }

    private void repeat(LaunchCommand command) {
        // given — 다른 테스트가 남긴 자손이 아직 끝나는 중일 수 있어 지금 있는 것은 기준선으로 둔다
        Set<Long> baseline = descendants();

        // when
        for (int round = 0; round < ROUNDS; round++) {
            TerminalStream stream =
                    launcher.launch(command, TerminalSize.DEFAULT).value().orElseThrow();
            AtomicBoolean printed = new AtomicBoolean();
            stream.subscribe(chunk -> printed.set(true));
            stream.start();
            // 프롬프트까지 떠서 콘솔 호스트가 붙은 셸을 닫는다
            await().atMost(FIRST_OUTPUT).untilTrue(printed);
            stream.close();
            assertThat(stream.onExit().toCompletableFuture()).succeedsWithin(CLEANUP);
        }

        // then
        await().atMost(CLEANUP).untilAsserted(() -> {
            Set<Long> extra = descendants();
            extra.removeAll(baseline);
            assertThat(describe(extra)).as("남은 자손 프로세스").isEmpty();
            assertThat(readerThreads()).as("남은 PTY 읽기 스레드").isEmpty();
        });
    }

    private static Set<Long> descendants() {
        return ProcessHandle.current()
                .descendants()
                .filter(ProcessHandle::isAlive)
                .map(ProcessHandle::pid)
                .collect(Collectors.toSet());
    }

    private static List<String> describe(Set<Long> pids) {
        return pids.stream()
                .map(pid -> pid + " "
                        + ProcessHandle.of(pid)
                                .flatMap(handle -> handle.info().command())
                                .orElse("?"))
                .toList();
    }

    private static List<String> readerThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(name -> name.startsWith(READER_THREAD))
                .toList();
    }
}
