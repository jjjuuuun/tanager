package tanager.application.port.out;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 키 설정(config {@code keys}, FR-CFG-01). 키 원문의 해석 · 충돌 검사는 UI(Keymap)가 한다.
 *
 * @param prefix prefix 키 원문. 비어 있으면 prefix 모드를 쓰지 않는다
 * @param bindings 기본 바인딩을 바꿀 동작 id → 키 원문(예 {@code "pane.splitRight" → "alt shift EQUALS"}). 둘 다 비어 있지 않다
 */
public record KeyConfig(Optional<String> prefix, Map<String, String> bindings) {
    private static final KeyConfig DEFAULTS = new KeyConfig(Optional.empty(), Map.of());

    public KeyConfig {
        Objects.requireNonNull(prefix, "prefix");
        if (prefix.filter(String::isBlank).isPresent()) {
            throw new IllegalArgumentException("prefix 가 비어 있다 — 없으면 Optional.empty()");
        }
        bindings = Map.copyOf(bindings);
        for (Map.Entry<String, String> binding : bindings.entrySet()) {
            if (binding.getKey().isBlank() || binding.getValue().isBlank()) {
                throw new IllegalArgumentException("빈 동작 id 나 키가 있다: " + binding);
            }
        }
    }

    /** 기본값: prefix 모드 꺼짐, 바꾼 바인딩 없음(기본 바인딩은 UI 가 안다). */
    public static KeyConfig defaults() {
        return DEFAULTS;
    }
}
