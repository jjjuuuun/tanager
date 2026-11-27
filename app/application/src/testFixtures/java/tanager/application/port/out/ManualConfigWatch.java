package tanager.application.port.out;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/** {@link ConfigWatch} 의 수작업 Fake. {@link #fire()} 가 살아 있는 구독의 콜백을 부른 스레드에서 차례로 부른다. 스레드 안전. */
public final class ManualConfigWatch implements ConfigWatch {
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    @Override
    public Subscription watch(Runnable changed) {
        Runnable registration = Objects.requireNonNull(changed, "changed")::run;
        listeners.add(registration);
        return () -> listeners.remove(registration);
    }

    /** 파일이 바뀐 것처럼 알린다. */
    public void fire() {
        for (Runnable listener : listeners) {
            listener.run();
        }
    }

    /** 살아 있는 구독 수. */
    public int watchers() {
        return listeners.size();
    }
}
