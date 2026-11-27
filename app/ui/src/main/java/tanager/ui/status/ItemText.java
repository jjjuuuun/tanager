package tanager.ui.status;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tanager.application.port.out.MonitorItem;
import tanager.domain.monitor.UsageLevel;

/**
 * 상태줄 리소스 항목 하나의 화면 값(#188). WSL 은 VM 메모리 · 배포판 디스크 두 항목이 같은 {@link MonitorItem#WSL} 이다.
 *
 * @param item 누르기 · 끄기 · 좁은 창에서 숨기기의 단위
 * @param bar 작은 막대의 채움 비율(0~1). 막대가 없는 항목은 비어 있다
 * @param tooltip 툴팁의 줄들(평문). 마지막 줄은 누르기 · 우클릭 안내다
 */
record ItemText(MonitorItem item, String text, UsageLevel level, Optional<Double> bar, List<String> tooltip) {

    ItemText {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(bar, "bar");
        tooltip = List.copyOf(tooltip);
    }
}
