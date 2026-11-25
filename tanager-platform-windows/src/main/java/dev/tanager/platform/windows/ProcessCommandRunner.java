package dev.tanager.platform.windows;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** {@link ProcessBuilder} 로 명령을 실행한다. 표준 에러는 버리고 표준 출력만 모은다. */
final class ProcessCommandRunner implements CommandRunner {

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
        closeStdin(process);
        // 출력을 끝까지 읽지 않으면 파이프 버퍼가 차서 자식 프로세스가 멈춘다.
        CompletableFuture<byte[]> stdout = readAll(process.getInputStream());
        try {
            if (!process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                process.destroyForcibly();
                return new CommandOutcome.TimedOut(timeout);
            }
            long remaining = Math.max(0, timeout.toNanos() - (System.nanoTime() - start));
            return new CommandOutcome.Exited(process.exitValue(), stdout.get(remaining, TimeUnit.NANOSECONDS));
        } catch (TimeoutException e) {
            // 프로세스는 끝났지만 손자 프로세스가 출력 파이프를 붙잡고 있는 경우.
            process.destroyForcibly();
            return new CommandOutcome.TimedOut(timeout);
        } catch (ExecutionException e) {
            return new CommandOutcome.Exited(process.exitValue(), new byte[0]);
        } catch (InterruptedException e) {
            // 호출 쪽이 기다리기를 그만뒀다 — 시간 초과와 같게 다루고 플래그는 되살린다.
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new CommandOutcome.TimedOut(Duration.ofNanos(System.nanoTime() - start));
        }
    }

    private static void closeStdin(Process process) {
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // 입력을 쓰지 않으므로 닫기 실패는 결과에 영향이 없다.
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
