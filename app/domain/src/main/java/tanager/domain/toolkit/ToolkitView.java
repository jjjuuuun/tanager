package tanager.domain.toolkit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tanager.domain.history.WorkspacePath;

/** 한 경로에서 쓸 수 있는 도구함 구성(FR-TK-01). 공급자들의 항목과 경고를 모은 읽기 전용 값이다. */
public record ToolkitView(WorkspacePath path, List<ToolkitItem> items, List<ToolkitWarning> warnings) {
    private static final Comparator<ToolkitItem> BY_NAME =
            Comparator.comparing(ToolkitItem::name, String.CASE_INSENSITIVE_ORDER);
    private static final ScopePrecedence IDENTITY_ONLY = new ScopePrecedence(Map.of());

    public ToolkitView {
        Objects.requireNonNull(path, "path");
        items = List.copyOf(items);
        warnings = List.copyOf(warnings);
    }

    /**
     * 종류(선언 순) → 범위(선언 순) → 이름(대소문자 무시, 같으면 원래 순서). 항목이 있는 종류 · 범위만 담는다. 어느 단계도 고칠 수 없는 순서 있는
     * 맵이다(FR-TK-03).
     */
    public Map<ToolkitKind, Map<ToolkitScope, List<ToolkitItem>>> grouped() {
        Map<ToolkitKind, Map<ToolkitScope, List<ToolkitItem>>> byKind = new EnumMap<>(ToolkitKind.class);
        for (ToolkitItem item : items) {
            byKind.computeIfAbsent(item.kind(), _ -> new EnumMap<>(ToolkitScope.class))
                    .computeIfAbsent(item.scope(), _ -> new ArrayList<>())
                    .add(item);
        }
        Map<ToolkitKind, Map<ToolkitScope, List<ToolkitItem>>> out = new LinkedHashMap<>();
        for (Map.Entry<ToolkitKind, Map<ToolkitScope, List<ToolkitItem>>> kind : byKind.entrySet()) {
            Map<ToolkitScope, List<ToolkitItem>> scopes = new LinkedHashMap<>();
            for (Map.Entry<ToolkitScope, List<ToolkitItem>> scope :
                    kind.getValue().entrySet()) {
                List<ToolkitItem> group = new ArrayList<>(scope.getValue());
                group.sort(BY_NAME);
                scopes.put(scope.getKey(), List.copyOf(group));
            }
            out.put(kind.getKey(), Collections.unmodifiableMap(scopes));
        }
        return Collections.unmodifiableMap(out);
    }

    /** 항목 · 경고에 나온 공급자(처음 나온 순서, 중복 없음). 공급자 칩용. */
    public List<ProviderId> providers() {
        Set<ProviderId> seen = new LinkedHashSet<>();
        for (ToolkitItem item : items) {
            seen.add(item.provider());
        }
        for (ToolkitWarning warning : warnings) {
            providerOf(warning).ifPresent(seen::add);
        }
        return List.copyOf(seen);
    }

    /** 한 공급자의 항목만 남긴 보기. 공급자를 가리지 않는 경고(파일 · 홈)는 그대로 둔다. */
    public ToolkitView only(ProviderId provider) {
        Objects.requireNonNull(provider, "provider");
        List<ToolkitItem> kept = new ArrayList<>();
        for (ToolkitItem item : items) {
            if (item.provider().equals(provider)) {
                kept.add(item);
            }
        }
        List<ToolkitWarning> keptWarnings = new ArrayList<>();
        for (ToolkitWarning warning : warnings) {
            if (providerOf(warning).map(provider::equals).orElse(true)) {
                keptWarnings.add(warning);
            }
        }
        return new ToolkitView(path, kept, keptWarnings);
    }

    /**
     * 가려진 항목을 가린 항목 — 같은 공급자 · 종류 · 이름(대소문자 구분) 중 가려지지 않은 것(FR-TK-05). 가려지지 않은 항목이면 empty. 공급자의 규칙(이름
     * 공간 · 대소문자 무시 · 독점)까지 따지려면 {@link Shadowing#winnerOf} 에 그 공급자의 표를 넘긴다.
     */
    public Optional<ToolkitItem> winnerOf(ToolkitItem shadowed) {
        return Shadowing.winnerOf(items, shadowed, IDENTITY_ONLY);
    }

    private static Optional<ProviderId> providerOf(ToolkitWarning warning) {
        return switch (warning) {
            case ToolkitWarning.ProviderFailed failed -> Optional.of(failed.provider());
            case ToolkitWarning.Unreadable _, ToolkitWarning.Malformed _, ToolkitWarning.HomeUnknown _ ->
                Optional.empty();
        };
    }
}
