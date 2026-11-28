package tanager.domain.toolkit;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 종류별 범위 우선순위(앞이 이긴다)와 가려짐 규칙. 표에 없는 종류는 가리지 않는다(메모리 · 훅 · 권한은 모두 적용). 실제 표는 도구 버전에 따라 바뀌므로
 * 공급자 어댑터가 정한다(SDD §7.1).
 *
 * <ul>
 *   <li>{@code order} — 종류마다 이기는 범위 순서.
 *   <li>{@code nameSpaces} — 이름을 같이 쓰는 종류 묶음과 이름 비교 방식({@link NameSpace}). 묶음에 없는 종류는 그 종류만의 {@link
 *       NameMatch#EXACT} 이름 공간이다.
 *   <li>{@code exclusive} — 그 종류의 항목이 그 범위에 하나라도 있으면 다른 범위의 같은 종류는 이름과 상관없이 쓰이지 않는다(관리형 MCP 독점 등).
 * </ul>
 *
 * <p>불변식: 한 종류의 목록에 같은 범위가 두 번 나오지 않는다. 이름 공간 · 독점 규칙의 종류는 {@code order} 에 있고, 한 종류는 이름 공간 하나에만
 * 든다.
 */
public record ScopePrecedence(
        Map<ToolkitKind, List<ToolkitScope>> order,
        List<NameSpace> nameSpaces,
        Map<ToolkitKind, ToolkitScope> exclusive) {

    /** 같은 이름인지 보는 방식. */
    public enum NameMatch {
        /** 글자 그대로(대소문자 구분). */
        EXACT,
        /**
         * 대소문자 · 공백 · 보이지 않는 글자(서식 문자)를 무시하고, 호환 형태(전각 글자 등, NFKC)와 대시 변형을 기본형으로 본다. 다른 문자 체계의 닮은
         * 글자는 다른 이름이다.
         */
        LOOSE
    }

    /**
     * 이름을 같이 쓰는 종류들. 같은 이름이면 앞 종류가 범위와 상관없이 이기고, 같은 종류끼리는 범위 순서로 정한다.
     *
     * <p>불변식: 종류가 하나 이상이고 겹치지 않는다.
     */
    public record NameSpace(List<ToolkitKind> kinds, NameMatch match) {
        public NameSpace {
            Objects.requireNonNull(match, "match");
            kinds = List.copyOf(kinds);
            if (kinds.isEmpty()) {
                throw new IllegalArgumentException("이름 공간에 종류가 없다");
            }
            if (EnumSet.copyOf(kinds).size() != kinds.size()) {
                throw new IllegalArgumentException("이름 공간에 같은 종류가 두 번 있다: " + kinds);
            }
        }
    }

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
        nameSpaces = List.copyOf(nameSpaces);
        Set<ToolkitKind> spaced = EnumSet.noneOf(ToolkitKind.class);
        for (NameSpace space : nameSpaces) {
            for (ToolkitKind kind : space.kinds()) {
                if (!order.containsKey(kind)) {
                    throw new IllegalArgumentException("이름 공간의 " + kind + " 가 우선순위 표에 없다");
                }
                if (!spaced.add(kind)) {
                    throw new IllegalArgumentException(kind + " 가 두 이름 공간에 있다");
                }
            }
        }
        exclusive = Map.copyOf(exclusive);
        for (ToolkitKind kind : exclusive.keySet()) {
            if (!order.containsKey(kind)) {
                throw new IllegalArgumentException("독점 규칙의 " + kind + " 가 우선순위 표에 없다");
            }
        }
    }

    /** 범위 순서만 있는 표(이름 공간은 종류마다 따로, 대소문자 구분, 독점 없음). */
    public ScopePrecedence(Map<ToolkitKind, List<ToolkitScope>> order) {
        this(order, List.of(), Map.of());
    }

    /** {@code kind} 가 든 이름 공간. 묶음에 없으면 그 종류만의 {@link NameMatch#EXACT} 이름 공간. */
    public NameSpace nameSpaceOf(ToolkitKind kind) {
        Objects.requireNonNull(kind, "kind");
        for (NameSpace space : nameSpaces) {
            if (space.kinds().contains(kind)) {
                return space;
            }
        }
        return new NameSpace(List.of(kind), NameMatch.EXACT);
    }

    /** {@code kind} 를 독점하는 범위. 독점 규칙이 없으면 empty. */
    public Optional<ToolkitScope> exclusiveScope(ToolkitKind kind) {
        return Optional.ofNullable(exclusive.get(Objects.requireNonNull(kind, "kind")));
    }

    /** {@code kinds} 가 이름을 같이 쓰는(앞이 이기는) 이름 공간을 더한 표. */
    public ScopePrecedence sharing(NameMatch match, ToolkitKind... kinds) {
        List<NameSpace> spaces = new ArrayList<>(nameSpaces);
        spaces.add(new NameSpace(List.of(kinds), match));
        return new ScopePrecedence(order, spaces, exclusive);
    }

    /** {@code kind} 의 항목이 {@code scope} 에 있으면 다른 범위의 {@code kind} 를 모두 가리는 규칙을 더한 표. */
    public ScopePrecedence exclusive(ToolkitKind kind, ToolkitScope scope) {
        Map<ToolkitKind, ToolkitScope> rules = new EnumMap<>(ToolkitKind.class);
        rules.putAll(exclusive);
        rules.put(Objects.requireNonNull(kind, "kind"), Objects.requireNonNull(scope, "scope"));
        return new ScopePrecedence(order, nameSpaces, rules);
    }
}
