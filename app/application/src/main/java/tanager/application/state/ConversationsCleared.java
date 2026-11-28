package tanager.application.state;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tanager.application.port.in.AppEvent;
import tanager.domain.history.ImportOrigin;
import tanager.domain.history.PastConversation;

/**
 * 가져온 대화 목록을 지우는 명령(FR-TK-09, #215) — 설정으로 가져오기를 껐을 때. {@code origin} 이 비면 모두 지운다. 이력의 출처
 * (claude-code 등)는 지우지 않는다 — 그 폴더에서 도구를 쓴 사실은 남는다. 목록이 바뀌었을 때만 {@link AppEvent.ConversationsChanged} 를
 * 낸다. 응답은 바꿨는가.
 */
public record ConversationsCleared(Optional<ImportOrigin> origin) implements Command<Boolean> {

    public ConversationsCleared {
        Objects.requireNonNull(origin, "origin");
    }

    @Override
    public Transition<Boolean> apply(AppState current) {
        return without(current, origin, List.of());
    }

    // origin(비면 모두)을 뺀 상태. 목록이 바뀌었으면 ConversationsChanged 를 then 앞에 둔다
    static Transition<Boolean> without(AppState current, Optional<ImportOrigin> origin, List<AppEvent> then) {
        Map<ImportOrigin, List<PastConversation>> next = new HashMap<>(current.conversations());
        origin.ifPresentOrElse(next::remove, next::clear);
        if (next.equals(current.conversations())) {
            return new Transition<>(current, then, !then.isEmpty());
        }
        AppState cleared = current.withConversations(next);
        List<AppEvent> events = new ArrayList<>();
        events.add(new AppEvent.ConversationsChanged(cleared.conversations()));
        events.addAll(then);
        return new Transition<>(cleared, events, true);
    }
}
