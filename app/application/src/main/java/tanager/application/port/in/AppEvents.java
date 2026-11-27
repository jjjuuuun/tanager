package tanager.application.port.in;

import java.util.function.Consumer;
import tanager.application.port.out.Subscription;

/** 애플리케이션 상태 이벤트를 UI 스레드(EDT)에서 받는다(ADR-0012). */
public interface AppEvents {

    /** {@code listener} 는 UI 스레드에서 발행 순서대로 불린다. 오래 막지 않는다. */
    Subscription subscribeOnUi(Consumer<AppEvent> listener);
}
