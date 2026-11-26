package dev.tanager.application.state;

import dev.tanager.application.port.in.AppEvent;
import dev.tanager.application.port.in.AppEvents;
import dev.tanager.application.port.out.Subscription;
import dev.tanager.application.port.out.UiDispatcher;
import java.lang.System.Logger.Level;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * {@link StateActor} 의 이벤트를 {@link UiDispatcher} 로 UI 스레드에 넘긴다. 해제 여부는 UI 쪽 실행 직전에 다시 확인해, 해제한 뒤에는 이미
 * 큐에 들어간 이벤트도 콜백으로 오지 않는다. UI 콜백이 던지면 UI 쪽에서 잡아 남기고 그 구독만 해제한다.
 */
public final class UiAppEvents implements AppEvents {
    private static final System.Logger LOG = System.getLogger(UiAppEvents.class.getName());

    private final StateActor actor;
    private final UiDispatcher dispatcher;

    public UiAppEvents(StateActor actor, UiDispatcher dispatcher) {
        this.actor = Objects.requireNonNull(actor, "actor");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    @Override
    public Subscription subscribeOnUi(Consumer<AppEvent> listener) {
        Objects.requireNonNull(listener, "listener");
        UiSubscription subscription = new UiSubscription();
        subscription.attach(actor.subscribe(event -> {
            if (!subscription.isCancelled()) {
                dispatcher.dispatch(() -> deliver(subscription, listener, event));
            }
        }));
        return subscription;
    }

    // UI 스레드에서 돈다
    private static void deliver(UiSubscription subscription, Consumer<AppEvent> listener, AppEvent event) {
        if (subscription.isCancelled()) {
            return;
        }
        try {
            listener.accept(event);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "UI 이벤트 구독자가 예외를 던져 구독을 해제한다", e);
            subscription.cancel();
        }
    }

    /** UI 쪽 해제 표시와 actor 쪽 구독을 함께 끊는다. */
    private static final class UiSubscription implements Subscription {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile Subscription upstream = () -> {};

        // cancel 과 겹쳐도 둘 중 하나는 상대의 쓰기를 본다(volatile · atomic) — upstream 은 반드시 끊긴다
        void attach(Subscription actorSubscription) {
            upstream = actorSubscription;
            if (cancelled.get()) {
                actorSubscription.cancel();
            }
        }

        boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public void cancel() {
            cancelled.set(true);
            upstream.cancel();
        }
    }
}
