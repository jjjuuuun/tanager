package tanager.application.state;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tanager.application.port.in.AppEvent;
import tanager.application.port.out.HistoryLoadNotice;
import tanager.application.port.out.LoadedHistory;
import tanager.domain.history.WorkHistory;

/**
 * 불러온 이력으로 바꾸는 명령(ADR-0013). 불러오는 동안 쌓인 방문을 불러온 이력 위에 순서대로 다시 적용해, 불러온 파일이 그 방문을 덮지
 * 않는다. 저장소를 연 방식에 따라 쓸 수 있는 상태나 읽기 전용이 된다.
 */
public record HistoryLoaded(LoadedHistory loaded) implements Command<Boolean> {
    public HistoryLoaded {
        Objects.requireNonNull(loaded, "loaded");
    }

    @Override
    public Transition<Boolean> apply(AppState current) {
        WorkHistory history = loaded.history();
        for (PendingVisit visit : pendingOf(current.store())) {
            history = history.recordVisit(visit.path(), visit.at());
        }
        List<AppEvent> events = new ArrayList<>();
        events.add(new AppEvent.HistoryChanged(history));
        for (HistoryLoadNotice notice : loaded.notices()) {
            events.add(new AppEvent.StoreNotice(notice.describe()));
        }
        return new Transition<>(current.withHistory(history).withStore(storeAfterLoad()), events, true);
    }

    private HistoryStore storeAfterLoad() {
        return switch (loaded.access()) {
            case WRITABLE -> new HistoryStore.Writable();
            case READ_ONLY ->
                new HistoryStore.ReadOnly(loaded.notices().stream()
                        .findFirst()
                        .map(HistoryLoadNotice::describe)
                        .orElse("이력 파일을 읽기 전용으로 열었다"));
        };
    }

    static List<PendingVisit> pendingOf(HistoryStore store) {
        return switch (store) {
            case HistoryStore.Loading(List<PendingVisit> pending) -> pending;
            case HistoryStore.Writable() -> List.of();
            case HistoryStore.ReadOnly(String reason) -> List.of();
        };
    }
}
