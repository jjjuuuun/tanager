package dev.tanager.platform.windows;

import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** {@link ProcessBuilder} 로 명령을 실행한다. 표준 에러는 버리고 표준 출력만 모은다. */
final class ProcessCommandRunner implements CommandRunner {
    private static final Logger LOG = System.getLogger(ProcessCommandRunner.class.getName());
    private static final Duration MIN_DRAIN_GRACE = Duration.ofMillis(200);

    @Override
    public CommandOutcome run(List<String> command, Duration timeout) {
        long start = System.nanoTime();
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException | RuntimeException e) {
            return new CommandOutcome.NotStarted(String.valueOf(e.getMessage()));
        }
        closeQuietly(process.getOutputStream());
        InputStream out = process.getInputStream();
        // 출력을 끝까지 읽지 않으면 파이프 버퍼가 차서 자식 프로세스가 멈춘다.
        CompletableFuture<byte[]> stdout = readAll(out);
        try {
            if (!process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                kill(process, out);
                return new CommandOutcome.TimedOut(timeout);
            }
            Duration drain = drainWait(timeout, Duration.ofNanos(System.nanoTime() - start));
            return new CommandOutcome.Exited(process.exitValue(), stdout.get(drain.toNanos(), TimeUnit.NANOSECONDS));
        } catch (TimeoutException e) {
            // 프로세스는 끝났는데 출력이 닫히지 않았다 — 손자 프로세스가 파이프를 물려받아 붙잡고 있다.
            kill(process, out);
            return new CommandOutcome.TimedOut(timeout);
        } catch (ExecutionException e) {
            LOG.log(Level.WARNING, "표준 출력을 읽지 못함 — 빈 출력으로 처리: {0}", e.getCause().getMessage());
            return new CommandOutcome.Exited(process.exitValue(), new byte[0]);
        } catch (InterruptedException e) {
            // 호출 쪽이 기다리기를 그만뒀다 — 시간 초과와 같게 다루고 플래그는 되살린다.
            Thread.currentThread().interrupt();
            kill(process, out);
            return new CommandOutcome.TimedOut(Duration.ofNanos(System.nanoTime() - start));
        }
    }

    /** 프로세스가 끝난 뒤 남은 출력을 기다릴 시간. 마감 직전에 끝난 정상 종료가 시간 초과로 분류되지 않게 최소 유예를 둔다. */
    static Duration drainWait(Duration timeout, Duration elapsed) {
        Duration remaining = timeout.minus(elapsed);
        return remaining.compareTo(MIN_DRAIN_GRACE) > 0 ? remaining : MIN_DRAIN_GRACE;
    }

    /**
     * 자손부터 강제 종료하고(부모가 먼저 죽으면 자손을 더 찾을 수 없다) 출력 스트림을 닫아 읽기 스레드를 풀어 준다. 이미 고아가 된
     * 손자는 찾을 수 없으므로 스트림 닫기가 마지막 수단이다.
     */
    private static void kill(Process process, InputStream out) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        closeQuietly(out);
    }

    private static void closeQuietly(AutoCloseable stream) {
        try {
            stream.close();
        } catch (Exception ignored) {
            // 더 쓰지 않을 스트림이라 닫기 실패는 결과에 영향이 없다.
        }
    }

    private static CompletableFuture<byte[]> readAll(InputStream in) {
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        Thread.ofVirtual().name("tanager-command-stdout").start(() -> {
            try (in) {
                result.complete(in.readAllBytes());
            } catch (IOException e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }
}
