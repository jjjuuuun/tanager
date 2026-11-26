package dev.tanager.ui;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 창을 닫은 뒤의 앱 종료(ADR-0011 개정): 종료 훅(남은 명령 · 미저장 이력 저장)과 셸 종료 대기를 한 기한 안에서 하고 끝에 반드시 프로세스를
 * 끝낸다. 훅이 멈추거나 Error 가 나도 기한을 넘기지 않는다.
 */
final class ExitSequence {
    private static final System.Logger LOG = System.getLogger(ExitSequence.class.getName());

    private ExitSequence() {}

    /**
     * @param hook 앱 종료 훅. 데몬 스레드에서 돌리고 기한까지만 기다린다
     * @param exited 셸이 끝나면 완료된다. 훅 뒤에 남은 기한까지만 기다린다
     * @param deadline {@link System#nanoTime} 기준 기한
     * @param halt 프로세스를 끝낸다({@code System.exit}). 무슨 일이 있어도 한 번 부른다
     */
    static void run(Runnable hook, CompletionStage<?> exited, long deadline, Runnable halt) {
        try {
            runHook(hook, deadline);
            awaitShell(exited, deadline);
        } finally {
            halt.run();
        }
    }

    // 훅이 저장 I/O 에서 멈춰도 종료가 기한을 넘기지 않게 따로 돌리고 남은 시간만큼만 기다린다
    private static void runHook(Runnable hook, long deadline) {
        Thread worker = Thread.ofPlatform()
                .daemon(true)
                .name("tanager-exit-hook")
                .start(() -> {
                    try {
                        hook.run();
                    } catch (RuntimeException e) {
                        LOG.log(System.Logger.Level.ERROR, "앱 종료 훅이 실패했다", e);
                    }
                });
        try {
            if (!worker.join(Duration.ofNanos(left(deadline)))) {
                LOG.log(System.Logger.Level.WARNING, "앱 종료 훅이 기한 안에 끝나지 않아 기다리지 않고 종료한다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitShell(CompletionStage<?> exited, long deadline) {
        try {
            exited.toCompletableFuture().get(left(deadline), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            LOG.log(System.Logger.Level.WARNING, "셸이 기한 안에 끝나지 않아 그대로 종료한다");
        } catch (ExecutionException e) {
            LOG.log(System.Logger.Level.WARNING, "셸 종료를 기다리다 오류", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long left(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }
}
