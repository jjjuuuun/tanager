package tanager.application.state;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 이력 저장소를 지금 쓸 수 있는지(ADR-0013). Loading · ReadOnly 에서는 저장하지 않는다. */
public sealed interface HistoryStore {

    /** 아직 불러오는 중이다. 그동안의 방문은 메모리 이력에 넣고 여기 쌓아 두었다가 불러온 이력 위에 다시 적용한다. */
    record Loading(List<PendingVisit> pending) implements HistoryStore {
        public Loading {
            pending = List.copyOf(pending);
        }

        /** {@code visit} 를 뒤에 붙인 새 상태. */
        public Loading queued(PendingVisit visit) {
            Objects.requireNonNull(visit, "visit");
            List<PendingVisit> next = new ArrayList<>(pending);
            next.add(visit);
            return new Loading(next);
        }
    }

    /** 불러왔고 저장할 수 있다. */
    record Writable() implements HistoryStore {}

    /** 저장하지 않는다(상위 버전 · 다른 인스턴스 · 불러오기 실패). 변경은 메모리에만 남는다. */
    record ReadOnly(String reason) implements HistoryStore {
        public ReadOnly {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
