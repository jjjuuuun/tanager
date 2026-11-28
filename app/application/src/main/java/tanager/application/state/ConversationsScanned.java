package tanager.application.state;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tanager.application.port.in.AppEvent;
import tanager.application.port.out.ConversationScan;
import tanager.domain.history.ImportBatch;
import tanager.domain.history.ImportOrigin;
import tanager.domain.history.ImportedVisit;
import tanager.domain.history.PastConversation;
import tanager.domain.history.WorkHistory;

/**
 * {@code origin} 의 대화 기록을 {@code at} 에 읽기 시작한 결과를 반영하는 명령(FR-TK-09, #215). 대화마다 그 cwd 를 가져온 방문으로 이력에
 * 합치고({@link WorkHistory#importVisits} — 같은 폴더의 대화는 기간을 합쳐 처음 = 가장 오래된, 마지막 = 가장 최근) 대화 목록은 메모리에만
 * 둔다. 다른 환경의 대화는 버린다.
 *
 * <p>설정이 그 출처의 가져오기를 끈 뒤(또는 설정을 읽기 전) 도착한 결과는 버린다 — 읽는 사이에 꺼졌을 수 있다. 이력 항목이 바뀌었을 때만
 * {@link AppEvent.HistoryChanged}, 목록이 바뀌었을 때만 {@link AppEvent.ConversationsChanged} 를 낸다. 워터마크만 바뀐 이력은 알리지
 * 않는다 — 다음 저장 때 같이 쓰인다. 응답은 이벤트를 냈는가.
 */
public record ConversationsScanned(ImportOrigin origin, Instant at, ConversationScan scan) implements Command<Boolean> {

    public ConversationsScanned {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(scan, "scan");
    }

    @Override
    public Transition<Boolean> apply(AppState current) {
        boolean wanted = current.settings()
                .map(config -> config.toolkit().importsConversations(origin.source()))
                .orElse(false);
        if (!wanted) {
            return new Transition<>(current, List.of(), false);
        }
        List<PastConversation> kept = new ArrayList<>();
        List<ImportedVisit> visits = new ArrayList<>();
        for (PastConversation conversation : scan.conversations()) {
            if (conversation.cwd().env().equals(origin.env())) {
                kept.add(conversation);
                visits.add(new ImportedVisit(conversation.cwd(), conversation.lastActive(), conversation.lastActive()));
            }
        }
        WorkHistory history =
                current.history().importVisits(new ImportBatch(origin.source(), origin.env(), at, visits));
        Map<ImportOrigin, List<PastConversation>> conversations = new HashMap<>(current.conversations());
        conversations.put(origin, kept);
        AppState next = current.withHistory(history).withConversations(conversations);
        List<AppEvent> events = new ArrayList<>();
        if (!history.entries().equals(current.history().entries())) {
            events.add(new AppEvent.HistoryChanged(history));
        }
        if (!next.conversations().equals(current.conversations())) {
            events.add(new AppEvent.ConversationsChanged(next.conversations()));
        }
        return new Transition<>(next, events, !events.isEmpty());
    }
}
