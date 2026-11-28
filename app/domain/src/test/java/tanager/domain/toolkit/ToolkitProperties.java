package tanager.domain.toolkit;

import static org.assertj.core.api.Assertions.assertThat;
import static tanager.domain.toolkit.ToolkitFixtures.CLAUDE;
import static tanager.domain.toolkit.ToolkitFixtures.CODEX;
import static tanager.domain.toolkit.ToolkitFixtures.PROJECT_DIR;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/** 가려짐 · 묶음의 불변식(#204). */
class ToolkitProperties {
    private static final ScopePrecedence PRECEDENCE = new ScopePrecedence(Map.of(
            ToolkitKind.SKILL, List.of(ToolkitScope.LOCAL, ToolkitScope.PROJECT, ToolkitScope.USER),
            ToolkitKind.SUBAGENT, List.of(ToolkitScope.MANAGED, ToolkitScope.PROJECT, ToolkitScope.USER),
            ToolkitKind.MCP_SERVER,
                    List.of(ToolkitScope.LOCAL, ToolkitScope.PROJECT, ToolkitScope.USER, ToolkitScope.MANAGED)));

    private record Identity(ProviderId provider, ToolkitKind kind, String name) {
        static Identity of(ToolkitItem item) {
            return new Identity(item.provider(), item.kind(), item.name());
        }
    }

    @Provide
    Arbitrary<List<ToolkitItem>> items() {
        Arbitrary<ToolkitItem> item = Combinators.combine(
                        Arbitraries.of(CLAUDE, CODEX),
                        Arbitraries.of(ToolkitKind.class),
                        Arbitraries.of("tdd", "TDD", "review", "github"),
                        Arbitraries.of(ToolkitScope.class),
                        Arbitraries.of(true, false))
                .as((provider, kind, name, scope, shadowed) ->
                        ToolkitFixtures.item(provider, kind, name, scope).withShadowed(shadowed));
        return item.list().ofMaxSize(30);
    }

    @Property
    @Label("mark 뒤 표에 있는 종류의 (공급자, 종류, 이름) 묶음마다 가려지지 않은 항목은 정확히 하나다")
    void atMostOneWinnerPerIdentity(@ForAll("items") List<ToolkitItem> items) {
        // when
        List<ToolkitItem> marked = Shadowing.mark(items, PRECEDENCE);

        // then
        Map<Identity, Integer> winners = new HashMap<>();
        for (ToolkitItem item : marked) {
            if (PRECEDENCE.order().containsKey(item.kind())) {
                winners.merge(Identity.of(item), item.shadowed() ? 0 : 1, Integer::sum);
            } else {
                assertThat(item.shadowed()).isFalse();
            }
        }
        assertThat(winners.values()).allMatch(count -> count == 1);
    }

    @Property
    @Label("mark 는 멱등이고 shadowed 외에는 순서 · 내용을 바꾸지 않는다")
    void markIsIdempotentAndKeepsOrder(@ForAll("items") List<ToolkitItem> items) {
        // when
        List<ToolkitItem> once = Shadowing.mark(items, PRECEDENCE);
        List<ToolkitItem> twice = Shadowing.mark(once, PRECEDENCE);

        // then
        assertThat(twice).isEqualTo(once);
        assertThat(once).hasSameSizeAs(items);
        for (int i = 0; i < items.size(); i++) {
            assertThat(once.get(i).withShadowed(false)).isEqualTo(items.get(i).withShadowed(false));
        }
    }

    @Property
    @Label("grouped() 의 항목 수 합은 items 수와 같다")
    void groupedKeepsEveryItem(@ForAll("items") List<ToolkitItem> items) {
        // given
        ToolkitView view = new ToolkitView(PROJECT_DIR, items, List.of());

        // when
        int total = 0;
        for (Map<ToolkitScope, List<ToolkitItem>> byScope : view.grouped().values()) {
            for (List<ToolkitItem> group : byScope.values()) {
                total += group.size();
            }
        }

        // then
        assertThat(total).isEqualTo(items.size());
    }
}
