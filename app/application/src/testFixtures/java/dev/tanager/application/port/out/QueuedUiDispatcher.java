package dev.tanager.application.port.out;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/** 넘어온 일을 큐에 쌓아 두고 {@link #runAll} 을 부를 때 부른 스레드에서 차례로 돌리는 {@link UiDispatcher} Fake. EDT 의 "나중에" 를 흉내 낸다. */
public final class QueuedUiDispatcher implements UiDispatcher {
    private final Queue<Runnable> queue = new ConcurrentLinkedQueue<>();

    @Override
    public void dispatch(Runnable task) {
        queue.add(task);
    }

    /** 아직 돌지 않은 일의 수. */
    public int pending() {
        return queue.size();
    }

    /** 큐가 빌 때까지(돌다가 새로 들어온 일 포함) 넘어온 순서대로 돌린다. */
    public void runAll() {
        Runnable task;
        while ((task = queue.poll()) != null) {
            task.run();
        }
    }
}
