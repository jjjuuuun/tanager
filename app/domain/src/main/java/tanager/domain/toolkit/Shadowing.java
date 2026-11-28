package tanager.domain.toolkit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 같은 이름이 여러 범위에 있을 때 어느 항목이 적용되는지 정한다(FR-TK-05). */
public final class Shadowing {
    private Shadowing() {}

    private record Identity(ProviderId provider, ToolkitKind kind, String name) {}

    /**
     * 같은 공급자 · 종류 · 이름(대소문자 구분)끼리 우선순위가 가장 높은 하나만 남기고 나머지를 {@code shadowed=true} 로 한다. 표에 없는
     * 종류는 모두 {@code shadowed=false}. 표에 없는 범위는 표의 범위보다 지고, 같은 순위끼리는 목록에서 먼저 나온 쪽이 이긴다 — 그래서 묶음마다
     * 이기는 항목은 정확히 하나다. 입력의 {@code shadowed} 는 보지 않으므로 멱등이다. 목록 순서는 바꾸지 않는다.
     */
    public static List<ToolkitItem> mark(List<ToolkitItem> items, ScopePrecedence precedence) {
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(precedence, "precedence");
        Map<Identity, Integer> winners = new HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            ToolkitItem item = items.get(i);
            List<ToolkitScope> order = precedence.order().get(item.kind());
            if (order == null) {
                continue;
            }
            Identity identity = identityOf(item);
            Integer best = winners.get(identity);
            if (best == null || rank(order, item) < rank(order, items.get(best))) {
                winners.put(identity, i);
            }
        }
        List<ToolkitItem> marked = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            ToolkitItem item = items.get(i);
            boolean listed = precedence.order().containsKey(item.kind());
            boolean shadowed = listed && winners.get(identityOf(item)) != i;
            marked.add(item.withShadowed(shadowed));
        }
        return List.copyOf(marked);
    }

    /** {@code candidate} 가 {@code shadowed} 와 같은 공급자 · 종류 · 이름인가. */
    static boolean sameIdentity(ToolkitItem candidate, ToolkitItem shadowed) {
        return identityOf(candidate).equals(identityOf(shadowed));
    }

    private static Identity identityOf(ToolkitItem item) {
        return new Identity(item.provider(), item.kind(), item.name());
    }

    private static int rank(List<ToolkitScope> order, ToolkitItem item) {
        int index = order.indexOf(item.scope());
        return index < 0 ? order.size() : index;
    }
}
