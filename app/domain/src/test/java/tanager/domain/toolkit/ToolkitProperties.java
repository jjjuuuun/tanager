package tanager.domain.toolkit;

import static org.assertj.core.api.Assertions.assertThat;
import static tanager.domain.toolkit.ToolkitFixtures.CLAUDE;
import static tanager.domain.toolkit.ToolkitFixtures.CODEX;
import static tanager.domain.toolkit.ToolkitFixtures.PROJECT_DIR;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/** 가려짐 · 묶음의 불변식(#204 · #247). */
class ToolkitProperties {
    private static final ScopePrecedence PRECEDENCE = new ScopePrecedence(Map.of(
            ToolkitKind.SKILL, List.of(ToolkitScope.LOCAL, ToolkitScope.PROJECT, ToolkitScope.USER),
            ToolkitKind.SUBAGENT, List.of(ToolkitScope.MANAGED, ToolkitScope.PROJECT, ToolkitScope.USER),
            ToolkitKind.MCP_SERVER,
                    List.of(ToolkitScope.LOCAL, ToolkitScope.PROJECT, ToolkitScope.USER, ToolkitScope.MANAGED)));

    /** 스킬 · 커맨드는 한 이름 공간(LOOSE, 스킬 우선), MCP 서버는 MANAGED 독점 — Claude 공급자와 같은 모양의 규칙. */
    private static final ScopePrecedence RULES = new ScopePrecedence(Map.of(
                    ToolkitKind.SKILL, List.of(ToolkitScope.MANAGED, ToolkitScope.USER, ToolkitScope.PROJECT),
                    ToolkitKind.COMMAND, List.of(ToolkitScope.USER, ToolkitScope.PROJECT),
                    ToolkitKind.SUBAGENT, List.of(ToolkitScope.MANAGED, ToolkitScope.PROJECT, ToolkitScope.USER),
                    ToolkitKind.MCP_SERVER,
                            List.of(ToolkitScope.MANAGED, ToolkitScope.LOCAL, ToolkitScope.PROJECT, ToolkitScope.USER)))
            .sharing(ScopePrecedence.NameMatch.LOOSE, ToolkitKind.SKILL, ToolkitKind.COMMAND)
            .exclusive(ToolkitKind.MCP_SERVER, ToolkitScope.MANAGED);

    private record Identity(ProviderId provider, ToolkitKind kind, String name) {
        static Identity of(ToolkitItem item) {
            return new Identity(item.provider(), item.kind(), item.name());
        }

        /** {@link #RULES} 의 같은 이름. 생성기의 이름은 ASCII 대소문자만 달라 LOOSE 는 소문자로 맞추면 된다. */
        static Identity ruled(ToolkitItem item) {
            boolean shared = item.kind() == ToolkitKind.SKILL || item.kind() == ToolkitKind.COMMAND;
            return shared
                    ? new Identity(
                            item.provider(), ToolkitKind.SKILL, item.name().toLowerCase(Locale.ROOT))
                    : of(item);
        }
    }

    /** 같은 공급자의 MCP 서버가 MANAGED 에 있어 MANAGED 밖의 MCP 서버가 쓰이지 않는가({@link #RULES}). */
    private static boolean excluded(List<ToolkitItem> items, ToolkitItem item) {
        if (item.kind() != ToolkitKind.MCP_SERVER || item.scope() == ToolkitScope.MANAGED) {
            return false;
        }
        for (ToolkitItem other : items) {
            if (other.provider().equals(item.provider())
                    && other.kind() == ToolkitKind.MCP_SERVER
                    && other.scope() == ToolkitScope.MANAGED) {
                return true;
            }
        }
        return false;
    }

    @Provide
    Arbitrary<List<ToolkitItem>> items() {
        Arbitrary<ToolkitItem> item = Combinators.combine(
                        Arbitraries.of(CLAUDE, CODEX),
                        Arbitraries.of(ToolkitKind.class),
                        Arbitraries.of("tdd", "TDD", "Tdd", "review", "github"),
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
    @Label("규칙이 있으면 같은 이름 묶음마다 가려지지 않은 항목은 독점에 막히지 않은 항목이 있으면 정확히 하나, 없으면 0 이다")
    void ruledOneWinnerPerIdentityUnlessExcluded(@ForAll("items") List<ToolkitItem> items) {
        // when
        List<ToolkitItem> marked = Shadowing.mark(items, RULES);

        // then
        Map<Identity, Integer> winners = new HashMap<>();
        Map<Identity, Boolean> open = new HashMap<>();
        for (ToolkitItem item : marked) {
            if (!RULES.order().containsKey(item.kind())) {
                assertThat(item.shadowed()).isFalse();
                continue;
            }
            Identity identity = Identity.ruled(item);
            boolean blocked = excluded(marked, item);
            winners.merge(identity, item.shadowed() ? 0 : 1, Integer::sum);
            open.merge(identity, !blocked, Boolean::logicalOr);
            if (blocked) {
                assertThat(item.shadowed()).isTrue();
            }
        }
        for (Map.Entry<Identity, Integer> entry : winners.entrySet()) {
            assertThat(entry.getValue()).isEqualTo(open.get(entry.getKey()) ? 1 : 0);
        }
    }

    @Property
    @Label("가려진 항목마다 가린 항목이 있고 같은 공급자의 가려지지 않은 항목이다. 가려지지 않은 항목은 가린 쪽이 없다")
    void everyShadowedItemHasAWinner(@ForAll("items") List<ToolkitItem> items) {
        for (ScopePrecedence precedence : List.of(PRECEDENCE, RULES)) {
            // when
            List<ToolkitItem> marked = Shadowing.mark(items, precedence);

            // then
            for (ToolkitItem item : marked) {
                if (item.shadowed()) {
                    ToolkitItem winner =
                            Shadowing.winnerOf(marked, item, precedence).orElseThrow();
                    assertThat(winner.shadowed()).isFalse();
                    assertThat(winner.provider()).isEqualTo(item.provider());
                } else {
                    assertThat(Shadowing.winnerOf(marked, item, precedence)).isEmpty();
                }
            }
        }
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
    @Label("규칙이 있어도 mark 는 멱등이고 shadowed 외에는 순서 · 내용을 바꾸지 않는다")
    void ruledMarkIsIdempotentAndKeepsOrder(@ForAll("items") List<ToolkitItem> items) {
        // when
        List<ToolkitItem> once = Shadowing.mark(items, RULES);
        List<ToolkitItem> twice = Shadowing.mark(once, RULES);

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
