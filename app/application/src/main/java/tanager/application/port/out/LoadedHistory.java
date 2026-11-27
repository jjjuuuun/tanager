package tanager.application.port.out;

import java.util.List;
import java.util.Objects;
import tanager.domain.history.WorkHistory;

/**
 * 읽은 이력과 저장소를 연 방식, 읽는 동안 생긴 일.
 *
 * @param history 읽은 이력(손상 · 없음이면 빈 이력)
 * @param access 이후 저장할 수 있는지
 * @param notices 복구 · 건너뜀 · 상위 버전 · 잠금 등 사용자에게 알릴 일
 */
public record LoadedHistory(WorkHistory history, StoreAccess access, List<HistoryLoadNotice> notices) {
    public LoadedHistory {
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(access, "access");
        notices = List.copyOf(notices);
    }
}
