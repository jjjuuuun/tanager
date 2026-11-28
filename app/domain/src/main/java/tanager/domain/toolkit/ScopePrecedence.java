package tanager.domain.toolkit;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 종류별 범위 우선순위(앞이 이긴다). 표에 없는 종류는 가리지 않는다(메모리 · 훅 · 권한은 모두 적용). 실제 표는 도구 버전에 따라 바뀌므로 공급자
 * 어댑터가 정한다(SDD §7.1).
 *
 * <p>불변식: 한 종류의 목록에 같은 범위가 두 번 나오지 않는다.
 */
public record ScopePrecedence(Map<ToolkitKind, List<ToolkitScope>> order) {
    public ScopePrecedence {
        Objects.requireNonNull(order, "order");
        Map<ToolkitKind, List<ToolkitScope>> copy = new EnumMap<>(ToolkitKind.class);
        for (Map.Entry<ToolkitKind, List<ToolkitScope>> entry : order.entrySet()) {
            List<ToolkitScope> scopes = List.copyOf(entry.getValue());
            Set<ToolkitScope> seen = EnumSet.noneOf(ToolkitScope.class);
            for (ToolkitScope scope : scopes) {
                if (!seen.add(scope)) {
                    throw new IllegalArgumentException(entry.getKey() + " 의 우선순위에 " + scope + " 가 두 번 있다");
                }
            }
            copy.put(entry.getKey(), scopes);
        }
        order = Map.copyOf(copy);
    }
}
