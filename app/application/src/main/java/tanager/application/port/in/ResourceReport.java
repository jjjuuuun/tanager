package tanager.application.port.in;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tanager.application.port.out.MonitorItem;
import tanager.application.port.out.ProbeError;
import tanager.domain.monitor.ResourceLevels;
import tanager.domain.monitor.ResourceSample;

/**
 * 한 주기의 리소스 표시 값(FR-MON-01~03).
 *
 * @param levels 항목별 경고 단계
 * @param items 지금 보일 항목(설정 + 상태줄에서 끄고 켠 것). 끈 항목은 표본에도 없다(디스크 · WSL) — CPU · 메모리는 한 번에 재므로 값은 있다
 * @param wslUnavailable 포커스 창이 WSL 인데 그 지표를 읽지 못한 이유. 있으면 {@code sample.wsl()} 은 비어 있다 — 상태줄은 '—'
 */
public record ResourceReport(
        ResourceSample sample, ResourceLevels levels, Set<MonitorItem> items, Optional<ProbeError> wslUnavailable) {

    public ResourceReport {
        Objects.requireNonNull(sample, "sample");
        Objects.requireNonNull(levels, "levels");
        items = Collections.unmodifiableSet(
                items.isEmpty() ? EnumSet.noneOf(MonitorItem.class) : EnumSet.copyOf(items));
        Objects.requireNonNull(wslUnavailable, "wslUnavailable");
        if (wslUnavailable.isPresent() && sample.wsl().isPresent()) {
            throw new IllegalArgumentException("WSL 지표가 있는데 읽지 못한 이유도 있다");
        }
    }
}
