package tanager.application.port.out;

import java.time.Duration;

/** 일을 나중에 한 번 돌리는 출력 포트. 테스트는 시간을 손으로 진행하는 Fake 를 쓴다. */
public interface Scheduler {

    /** {@code delay} 뒤에 {@code task} 를 한 번 돌린다. 돌기 전에 취소할 수 있다. 어느 스레드에서 불러도 된다. */
    Cancellable schedule(Duration delay, Runnable task);
}
