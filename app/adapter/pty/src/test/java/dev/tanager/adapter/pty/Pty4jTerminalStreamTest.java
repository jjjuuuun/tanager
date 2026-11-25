package dev.tanager.adapter.pty;

import static org.awaitility.Awaitility.await;

import com.pty4j.PtyProcess;
import com.pty4j.WinSize;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.OS;

/** 실제 PTY 없이 {@link Pty4jTerminalStream} 의 프로세스 정리 규칙을 본다. 어느 OS 에서나 돈다. */
class Pty4jTerminalStreamTest {
    private static final Duration WAIT = Duration.ofSeconds(10);

    private Process bystander;

    @AfterEach
    void killBystander() {
        if (bystander != null) {
            bystander.destroyForcibly();
        }
    }

    /** 오래 사는 무관한 프로세스. PID 가 재사용돼 끝난 세션의 PID 를 이 프로세스가 갖게 된 상황을 흉내 낸다. */
    private static Process startBystander() throws IOException {
        List<String> command =
                OS.current() == OS.WINDOWS ? List.of("ping", "-n", "600", "127.0.0.1") : List.of("sleep", "600");
        return new ProcessBuilder(command).redirectErrorStream(true).start();
    }

    @Test
    @DisplayName("끝난 세션을 close 해도 그 PID 를 물려받은 무관한 프로세스는 죽이지 않는다")
    void closeAfterExitDoesNotKillReusedPid() throws IOException {
        // given: 출력 없이 끝나는 세션인데 PID 는 살아 있는 다른 프로세스의 것이다
        bystander = startBystander();
        Pty4jTerminalStream stream = new Pty4jTerminalStream(new FinishedProcess(bystander.pid()));
        stream.start();
        await().atMost(WAIT).until(() -> stream.onExit().toCompletableFuture().isDone());

        // when
        stream.close();

        // then
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(4)).until(bystander::isAlive);
    }

    /** 이미 끝난 PTY 프로세스(출력 없음, 종료 코드 0). {@code destroy} 를 불러도 아무것도 하지 않는다. */
    private static final class FinishedProcess extends PtyProcess {
        private final long pid;

        FinishedProcess(long pid) {
            this.pid = pid;
        }

        @Override
        public long pid() {
            return pid;
        }

        @Override
        public void setWinSize(WinSize winSize) {}

        @Override
        public WinSize getWinSize() {
            return new WinSize(80, 24);
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public boolean isAlive() {
            return false;
        }

        @Override
        public void destroy() {}
    }
}
