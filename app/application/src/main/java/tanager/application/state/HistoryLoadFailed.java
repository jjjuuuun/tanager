package tanager.application.state;

import java.util.List;
import java.util.Objects;
import tanager.application.port.in.AppEvent;
import tanager.application.port.out.HistoryStoreError;

/**
 * 이력을 불러오지 못했을 때의 명령(ADR-0013). 파일을 모르는 채 덮어쓰지 않게 읽기 전용이 되고, 불러오는 동안의 방문은 메모리 이력에
 * 그대로 남는다.
 */
public record HistoryLoadFailed(HistoryStoreError error) implements Command<Boolean> {
    public HistoryLoadFailed {
        Objects.requireNonNull(error, "error");
    }

    @Override
    public Transition<Boolean> apply(AppState current) {
        String reason = error.describe();
        return new Transition<>(
                current.withStore(new HistoryStore.ReadOnly(reason)), List.of(new AppEvent.StoreNotice(reason)), true);
    }
}
