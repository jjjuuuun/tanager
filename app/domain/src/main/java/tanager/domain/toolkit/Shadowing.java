package tanager.domain.toolkit;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 같은 이름이 여러 범위에 있을 때 어느 항목이 적용되는지 정한다(FR-TK-05). 규칙은 {@link ScopePrecedence}. */
public final class Shadowing {
    private Shadowing() {}

    /** 같은 이름의 묶음 — 공급자, 이름 공간(종류 목록), 비교용으로 접은 이름. */
    private record Identity(ProviderId provider, List<ToolkitKind> space, String key) {}

    /** 독점 규칙이 걸린 (공급자, 종류). */
    private record Exclusion(ProviderId provider, ToolkitKind kind) {}

    /**
     * 같은 공급자 · 이름 공간 · 이름끼리 우선순위가 가장 높은 하나만 남기고 나머지를 {@code shadowed=true} 로 한다. 이름 공간 안에서는 앞 종류가
     * 이기고, 같은 종류끼리는 범위 순서로 정한다. 독점 범위에 그 공급자 · 종류의 항목이 있으면 다른 범위의 같은 종류는 모두 가려진다. 표에 없는 종류는
     * 모두 {@code shadowed=false}. 표에 없는 범위는 표의 범위보다 지고, 같은 순위끼리는 목록에서 먼저 나온 쪽이 이긴다 — 그래서 묶음마다 이기는 항목은
     * 독점에 막히지 않은 항목이 있으면 정확히 하나, 없으면 없다. 입력의 {@code shadowed} 는 보지 않으므로 멱등이다. 목록 순서는 바꾸지 않는다.
     */
    public static List<ToolkitItem> mark(List<ToolkitItem> items, ScopePrecedence precedence) {
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(precedence, "precedence");
        Set<Exclusion> exclusions = exclusions(items, precedence);
        Map<Identity, Integer> winners = new HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            ToolkitItem item = items.get(i);
            if (!precedence.order().containsKey(item.kind()) || excluded(item, exclusions, precedence)) {
                continue;
            }
            Identity identity = identityOf(item, precedence);
            Integer best = winners.get(identity);
            if (best == null || beats(item, items.get(best), precedence)) {
                winners.put(identity, i);
            }
        }
        List<ToolkitItem> marked = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            ToolkitItem item = items.get(i);
            boolean listed = precedence.order().containsKey(item.kind());
            Integer winner = listed ? winners.get(identityOf(item, precedence)) : null;
            boolean shadowed = listed && (winner == null || winner != i);
            marked.add(item.withShadowed(shadowed));
        }
        return List.copyOf(marked);
    }

    /**
     * 가려진 항목을 가린 항목. {@code marked} 는 같은 {@code precedence} 로 {@link #mark} 한 목록이다. 같은 이름 묶음에서 가려지지 않은 것, 없으면
     * (독점에 막힌 항목이면) 독점 범위의 같은 공급자 · 종류 중 가려지지 않은 첫 항목. 가려지지 않은 항목이면 empty.
     */
    public static Optional<ToolkitItem> winnerOf(
            List<ToolkitItem> marked, ToolkitItem shadowed, ScopePrecedence precedence) {
        Objects.requireNonNull(marked, "marked");
        Objects.requireNonNull(shadowed, "shadowed");
        Objects.requireNonNull(precedence, "precedence");
        if (!shadowed.shadowed()) {
            return Optional.empty();
        }
        Identity identity = identityOf(shadowed, precedence);
        for (ToolkitItem item : marked) {
            if (!item.shadowed() && identityOf(item, precedence).equals(identity)) {
                return Optional.of(item);
            }
        }
        if (!excluded(shadowed, exclusions(marked, precedence), precedence)) {
            return Optional.empty();
        }
        ToolkitScope owner = precedence.exclusive().get(shadowed.kind());
        for (ToolkitItem item : marked) {
            if (!item.shadowed()
                    && item.provider().equals(shadowed.provider())
                    && item.kind() == shadowed.kind()
                    && item.scope() == owner) {
                return Optional.of(item);
            }
        }
        return Optional.empty();
    }

    private static Set<Exclusion> exclusions(List<ToolkitItem> items, ScopePrecedence precedence) {
        Set<Exclusion> active = new HashSet<>();
        for (ToolkitItem item : items) {
            if (item.scope() == precedence.exclusive().get(item.kind())) {
                active.add(new Exclusion(item.provider(), item.kind()));
            }
        }
        return active;
    }

    private static boolean excluded(ToolkitItem item, Set<Exclusion> exclusions, ScopePrecedence precedence) {
        return exclusions.contains(new Exclusion(item.provider(), item.kind()))
                && item.scope() != precedence.exclusive().get(item.kind());
    }

    private static Identity identityOf(ToolkitItem item, ScopePrecedence precedence) {
        ScopePrecedence.NameSpace space = spaceOf(item.kind(), precedence);
        String key = switch (space.match()) {
            case EXACT -> item.name();
            case LOOSE -> loose(item.name());
        };
        return new Identity(item.provider(), space.kinds(), key);
    }

    private static ScopePrecedence.NameSpace spaceOf(ToolkitKind kind, ScopePrecedence precedence) {
        for (ScopePrecedence.NameSpace space : precedence.nameSpaces()) {
            if (space.kinds().contains(kind)) {
                return space;
            }
        }
        return new ScopePrecedence.NameSpace(List.of(kind), ScopePrecedence.NameMatch.EXACT);
    }

    /** {@code candidate} 가 지금 이기는 {@code best} 를 이기는가 — 이름 공간의 종류 순서, 다음 범위 순서. 같으면 먼저 나온 쪽({@code best}). */
    private static boolean beats(ToolkitItem candidate, ToolkitItem best, ScopePrecedence precedence) {
        List<ToolkitKind> kinds = spaceOf(candidate.kind(), precedence).kinds();
        int byKind = Integer.compare(kinds.indexOf(candidate.kind()), kinds.indexOf(best.kind()));
        if (byKind != 0) {
            return byKind < 0;
        }
        List<ToolkitScope> order = precedence.order().get(candidate.kind());
        return rank(order, candidate) < rank(order, best);
    }

    private static int rank(List<ToolkitScope> order, ToolkitItem item) {
        int index = order.indexOf(item.scope());
        return index < 0 ? order.size() : index;
    }

    /** {@link ScopePrecedence.NameMatch#LOOSE} 비교용 이름. */
    private static String loose(String name) {
        String compatible = Normalizer.normalize(name, Normalizer.Form.NFKC);
        StringBuilder out = new StringBuilder(compatible.length());
        for (int i = 0; i < compatible.length(); ) {
            int cp = compatible.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp) || type == Character.FORMAT) {
                continue;
            }
            out.appendCodePoint(type == Character.DASH_PUNCTUATION ? '-' : cp);
        }
        return out.toString().toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
    }
}
