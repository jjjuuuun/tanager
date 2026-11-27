package tanager.application.service;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * 계산을 처음 부른 스레드에서 한 번만 하고 결과를 기억한다. 계산 중에 부른 다른 스레드는 그 결과를 기다린다. 계산이 예외로 끝나면 그
 * 실패도 기억하고, 부를 때마다 {@link IllegalStateException} 을 던진다.
 */
final class Once<T> {
    // FutureTask.run 은 처음 한 번만 실행되고, 동시에 부른 다른 스레드는 get 에서 그 결과를 기다린다.
    private final FutureTask<T> task;
    private final String failure;

    Once(Callable<T> compute, String failure) {
        this.task = new FutureTask<>(Objects.requireNonNull(compute, "compute"));
        this.failure = Objects.requireNonNull(failure, "failure");
    }

    T get() {
        task.run();
        return await();
    }

    // 다른 스레드가 계산 중일 때 인터럽트를 받아도 결과를 기다리고, 끝난 뒤 인터럽트 상태를 되살린다.
    private T await() {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return task.get();
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (ExecutionException e) {
                    throw new IllegalStateException(failure, e.getCause());
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
