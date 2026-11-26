package dev.tanager.application.port.out;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 넘어온 일을 기록하고 부른 스레드에서 바로 실행하는 {@link UiDispatcher} Fake. */
public final class DirectUiDispatcher implements UiDispatcher {
    private final List<Runnable> dispatched = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void dispatch(Runnable task) {
        dispatched.add(task);
        task.run();
    }

    /** dispatch 가 불린 횟수. */
    public int dispatchCount() {
        return dispatched.size();
    }
}
