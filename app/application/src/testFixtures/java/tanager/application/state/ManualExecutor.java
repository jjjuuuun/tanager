package tanager.application.state;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;

/**
 * 제출된 일을 큐에 쌓아 두고 {@link #runNext} · {@link #runAll} 을 부를 때 부른 스레드에서 차례로 돌리는 결정적 실행기. 스레드 안전하지
 * 않다.
 */
public final class ManualExecutor implements Executor {
    private final Queue<Runnable> queue = new ArrayDeque<>();

    @Override
    public void execute(Runnable task) {
        queue.add(task);
    }

    /** 아직 돌지 않은 일의 수. */
    public int pending() {
        return queue.size();
    }

    /** 맨 앞의 일 하나만 돌린다. 돌린 일이 있으면 {@code true}. */
    public boolean runNext() {
        Runnable task = queue.poll();
        if (task == null) {
            return false;
        }
        task.run();
        return true;
    }

    /** 큐가 빌 때까지(돌다가 새로 들어온 일 포함) 제출 순서대로 돌린다. */
    public void runAll() {
        while (runNext()) {
            // 다음 일
        }
    }
}
