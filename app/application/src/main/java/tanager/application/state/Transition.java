package tanager.application.state;

import java.util.List;
import java.util.Objects;
import tanager.application.port.in.AppEvent;

/** 명령 하나의 결과: 다음 상태 · 발행할 이벤트(순서대로) · 제출한 쪽에 돌려줄 응답. */
public record Transition<R>(AppState next, List<AppEvent> events, R reply) {

    public Transition {
        Objects.requireNonNull(next, "next");
        events = List.copyOf(events);
        Objects.requireNonNull(reply, "reply");
    }
}
