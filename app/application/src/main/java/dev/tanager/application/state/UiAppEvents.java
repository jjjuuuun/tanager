package dev.tanager.application.state;

import dev.tanager.application.port.in.AppEvents;
import dev.tanager.application.port.out.Subscription;
import dev.tanager.application.port.out.UiDispatcher;
import java.util.Objects;
import java.util.function.Consumer;

/** {@link StateActor} 의 이벤트를 {@link UiDispatcher} 로 UI 스레드에 넘긴다. */
public final class UiAppEvents implements AppEvents {
    private final StateActor actor;
    private final UiDispatcher dispatcher;

    public UiAppEvents(StateActor actor, UiDispatcher dispatcher) {
        this.actor = Objects.requireNonNull(actor, "actor");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    @Override
    public Subscription subscribeOnUi(Consumer<AppEvent> listener) {
        Objects.requireNonNull(listener, "listener");
        return actor.subscribe(event -> dispatcher.dispatch(() -> listener.accept(event)));
    }
}
