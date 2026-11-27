package tanager.ui.status;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tanager.application.port.out.MonitorItem;

/** 좁은 상태줄에서 보일 리소스 항목(순수, #188). 폭이 모자라면 WSL → 디스크 → 메모리 순으로 통째로 숨기고, CPU 는 끝까지 남긴다. */
final class StatusFit {
    private static final List<MonitorItem> DROP_ORDER = List.of(MonitorItem.WSL, MonitorItem.DISK, MonitorItem.MEMORY);

    private StatusFit() {}

    /** 항목 하나가 차지하는 폭(px, 간격 포함). WSL 처럼 한 항목이 여럿이면 같은 {@code item} 으로 여러 번 준다. */
    record Width(MonitorItem item, int pixels) {
        Width {
            Objects.requireNonNull(item, "item");
            if (pixels < 0) {
                throw new IllegalArgumentException("폭은 0 이상이어야 한다: " + pixels);
            }
        }
    }

    /** {@code width} 안에 들어가는 항목(열거 순서). 주어진 항목만 돌려준다. */
    static Set<MonitorItem> visible(List<Width> items, int width) {
        Map<MonitorItem, Integer> byItem = new EnumMap<>(MonitorItem.class);
        long total = 0;
        for (Width item : items) {
            byItem.merge(item.item(), item.pixels(), Integer::sum);
            total += item.pixels();
        }
        Set<MonitorItem> shown = byItem.isEmpty() ? EnumSet.noneOf(MonitorItem.class) : EnumSet.copyOf(byItem.keySet());
        for (MonitorItem drop : DROP_ORDER) {
            if (total <= width) {
                break;
            }
            if (shown.remove(drop)) {
                total -= byItem.get(drop);
            }
        }
        return shown;
    }
}
