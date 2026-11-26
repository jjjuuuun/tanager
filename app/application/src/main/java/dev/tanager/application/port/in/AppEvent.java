package dev.tanager.application.port.in;

import dev.tanager.domain.history.WorkHistory;
import java.util.Objects;

/** 명령이 상태를 바꾼 뒤 구독자에게 알리는 이벤트. 세부 변경 대신 바뀐 값을 싣는다(ADR-0012). */
public sealed interface AppEvent {

    /** 작업 경로 이력이 바뀌었다. 새 이력 전체를 싣는다. */
    record HistoryChanged(WorkHistory history) implements AppEvent {
        public HistoryChanged {
            Objects.requireNonNull(history, "history");
        }
    }
}
