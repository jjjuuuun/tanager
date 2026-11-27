package tanager.adapter.persistence;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tanager.application.port.out.AnimationMode;
import tanager.application.port.out.AppearanceConfig;
import tanager.application.port.out.ConfigWarning;
import tanager.application.port.out.ConfigWarning.IgnoredReason;
import tanager.application.port.out.HistoryConfig;
import tanager.application.port.out.KeyConfig;
import tanager.application.port.out.MonitorConfig;
import tanager.application.port.out.MonitorItem;
import tanager.application.port.out.TerminalConfig;
import tanager.application.port.out.ThemeMode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * config.json 의 appearance · terminal · history · monitor · keys 를 읽는다(SDD §9.3, ADR-0009). 키마다 따로 판단한다 — 형식이 틀린 키는
 * 그 키만 기본값으로 두고, 범위 밖 숫자는 경계로 고정하며, 배열 · 객체 안의 틀린 항목은 그 항목만 뺀다. 경고는 만들 때 받은 목록에 쌓는다.
 * 읽기 한 번에 하나씩 만든다.
 */
final class ConfigSections {
    private final List<ConfigWarning> warnings;

    ConfigSections(List<ConfigWarning> warnings) {
        this.warnings = warnings;
    }

    AppearanceConfig appearance(JsonNode node) {
        AppearanceConfig defaults = AppearanceConfig.defaults();
        // cornerRadius 는 읽지 않는다 — 모서리는 테마 토큰이 정한다
        return section(node, "appearance")
                .map(s -> new AppearanceConfig(
                        enumValue(s, "appearance.theme", "theme", ThemeMode.class, defaults.theme()),
                        enumValue(
                                s, "appearance.animations", "animations", AnimationMode.class, defaults.animations())))
                .orElse(defaults);
    }

    TerminalConfig terminal(JsonNode node) {
        TerminalConfig defaults = TerminalConfig.defaults();
        return section(node, "terminal")
                .map(s -> new TerminalConfig(
                        nonBlankString(s.get("font"), "terminal.font").orElse(defaults.font()),
                        boundedInt(
                                s,
                                "terminal.size",
                                "size",
                                TerminalConfig.MIN_SIZE,
                                TerminalConfig.MAX_SIZE,
                                defaults.size()),
                        boundedDouble(
                                s,
                                "terminal.lineHeight",
                                "lineHeight",
                                TerminalConfig.MIN_LINE_HEIGHT,
                                TerminalConfig.MAX_LINE_HEIGHT,
                                defaults.lineHeight()),
                        boundedInt(
                                s,
                                "terminal.scrollback",
                                "scrollback",
                                TerminalConfig.MIN_SCROLLBACK,
                                TerminalConfig.MAX_SCROLLBACK,
                                defaults.scrollback())))
                .orElse(defaults);
    }

    HistoryConfig history(JsonNode node) {
        HistoryConfig defaults = HistoryConfig.defaults();
        return section(node, "history")
                .map(s -> new HistoryConfig(
                        boundedInt(
                                s,
                                "history.maxEntries",
                                "maxEntries",
                                HistoryConfig.MIN_ENTRIES,
                                HistoryConfig.MAX_ENTRIES,
                                defaults.maxEntries()),
                        globs(s.get("exclude"), "history.exclude").orElse(defaults.exclude())))
                .orElse(defaults);
    }

    MonitorConfig monitor(JsonNode node) {
        MonitorConfig defaults = MonitorConfig.defaults();
        Optional<ObjectNode> monitor = section(node, "monitor");
        if (monitor.isEmpty()) {
            return defaults;
        }
        ObjectNode s = monitor.get();
        Optional<ObjectNode> thresholds = section(s.get("thresholds"), "monitor.thresholds");
        return new MonitorConfig(
                bool(s.get("enabled"), "monitor.enabled").orElse(defaults.enabled()),
                boundedInt(
                        s,
                        "monitor.intervalSec",
                        "intervalSec",
                        MonitorConfig.MIN_INTERVAL_SEC,
                        MonitorConfig.MAX_INTERVAL_SEC,
                        defaults.intervalSec()),
                items(s.get("items")).orElse(defaults.items()),
                thresholds.map(t -> threshold(t, "cpu", defaults.cpu())).orElse(defaults.cpu()),
                thresholds
                        .map(t -> boundedInt(
                                t,
                                "monitor.thresholds.cpuSustainSec",
                                "cpuSustainSec",
                                MonitorConfig.MIN_CPU_SUSTAIN_SEC,
                                Integer.MAX_VALUE,
                                defaults.cpuSustainSec()))
                        .orElse(defaults.cpuSustainSec()),
                thresholds.map(t -> threshold(t, "memory", defaults.memory())).orElse(defaults.memory()),
                thresholds.map(t -> threshold(t, "disk", defaults.disk())).orElse(defaults.disk()));
    }

    KeyConfig keys(JsonNode node) {
        KeyConfig defaults = KeyConfig.defaults();
        return section(node, "keys")
                .map(s -> new KeyConfig(prefix(s.get("prefix")), bindings(s.get("bindings"))))
                .orElse(defaults);
    }

    // ---- 항목 ----

    // 없으면 비어 있다(경고 없음). 객체가 아니면 경고하고 비어 있다
    private Optional<ObjectNode> section(JsonNode node, String key) {
        if (node == null) {
            return Optional.empty();
        }
        if (node instanceof ObjectNode object) {
            return Optional.of(object);
        }
        ignore(key, IgnoredReason.NOT_OBJECT);
        return Optional.empty();
    }

    // 대소문자를 가리지 않는다 — 사람이 고치는 파일이다
    private <E extends Enum<E>> E enumValue(ObjectNode section, String key, String name, Class<E> type, E fallback) {
        JsonNode node = section.get(name);
        if (node == null) {
            return fallback;
        }
        return nonBlankString(node, key)
                .flatMap(raw -> constant(type, raw, key))
                .orElse(fallback);
    }

    private <E extends Enum<E>> Optional<E> constant(Class<E> type, String raw, String key) {
        String wanted = raw.strip().toUpperCase(Locale.ROOT);
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(wanted)) {
                return Optional.of(constant);
            }
        }
        ignore(key, IgnoredReason.UNKNOWN_VALUE);
        return Optional.empty();
    }

    private int boundedInt(ObjectNode section, String key, String name, int min, int max, int fallback) {
        JsonNode node = section.get(name);
        if (node == null) {
            return fallback;
        }
        if (!node.isIntegralNumber()) {
            ignore(key, IgnoredReason.NOT_INTEGER);
            return fallback;
        }
        BigInteger value = node.bigIntegerValue();
        int used;
        if (value.compareTo(BigInteger.valueOf(min)) < 0) {
            used = min;
        } else if (value.compareTo(BigInteger.valueOf(max)) > 0) {
            used = max;
        } else {
            return value.intValueExact();
        }
        warnings.add(new ConfigWarning.OutOfRange(key, node.toString(), Integer.toString(used)));
        return used;
    }

    private double boundedDouble(ObjectNode section, String key, String name, double min, double max, double fallback) {
        JsonNode node = section.get(name);
        if (node == null) {
            return fallback;
        }
        if (!node.isNumber()) {
            ignore(key, IgnoredReason.NOT_NUMBER);
            return fallback;
        }
        double value = node.doubleValue();
        double used = Math.clamp(value, min, max);
        if (used != value) {
            warnings.add(new ConfigWarning.OutOfRange(key, node.toString(), Double.toString(used)));
        }
        return used;
    }

    // 위 경계(1)는 고정하고, 열린 아래 경계(0 이하)는 고정할 값이 없어 기본값을 쓴다
    private double threshold(ObjectNode thresholds, String name, double fallback) {
        String key = "monitor.thresholds." + name;
        JsonNode node = thresholds.get(name);
        if (node == null) {
            return fallback;
        }
        if (!node.isNumber()) {
            ignore(key, IgnoredReason.NOT_NUMBER);
            return fallback;
        }
        double value = node.doubleValue();
        if (MonitorConfig.isThreshold(value)) {
            return value;
        }
        double used = value > MonitorConfig.MAX_THRESHOLD ? MonitorConfig.MAX_THRESHOLD : fallback;
        warnings.add(new ConfigWarning.OutOfRange(key, node.toString(), Double.toString(used)));
        return used;
    }

    private Optional<Boolean> bool(JsonNode node, String key) {
        if (node == null) {
            return Optional.empty();
        }
        if (node.isBoolean()) {
            return Optional.of(node.booleanValue());
        }
        ignore(key, IgnoredReason.NOT_BOOLEAN);
        return Optional.empty();
    }

    private Optional<List<String>> globs(JsonNode node, String key) {
        return array(node, key).map(array -> {
            List<String> globs = new ArrayList<>();
            for (int i = 0; i < array.size(); i++) {
                nonBlankString(array.get(i), key + "[" + i + "]").ifPresent(globs::add);
            }
            return globs;
        });
    }

    private Optional<Set<MonitorItem>> items(JsonNode node) {
        String key = "monitor.items";
        return array(node, key).map(array -> {
            Set<MonitorItem> items = EnumSet.noneOf(MonitorItem.class);
            for (int i = 0; i < array.size(); i++) {
                String element = key + "[" + i + "]";
                nonBlankString(array.get(i), element)
                        .flatMap(raw -> constant(MonitorItem.class, raw, element))
                        .ifPresent(items::add);
            }
            return items;
        });
    }

    private Optional<ArrayNode> array(JsonNode node, String key) {
        if (node == null) {
            return Optional.empty();
        }
        if (node instanceof ArrayNode array) {
            return Optional.of(array);
        }
        ignore(key, IgnoredReason.NOT_ARRAY);
        return Optional.empty();
    }

    // null · 빈 문자열은 "prefix 모드 끔"이라 경고하지 않는다
    private Optional<String> prefix(JsonNode node) {
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        if (node.isString()) {
            return Optional.of(node.stringValue()).filter(raw -> !raw.isBlank());
        }
        ignore("keys.prefix", IgnoredReason.NOT_STRING);
        return Optional.empty();
    }

    private Map<String, String> bindings(JsonNode node) {
        Map<String, String> bindings = new HashMap<>();
        Optional<ObjectNode> object = section(node, "keys.bindings");
        if (object.isEmpty()) {
            return bindings;
        }
        for (Map.Entry<String, JsonNode> entry : object.get().properties()) {
            if (entry.getKey().isBlank()) {
                ignore("keys.bindings", IgnoredReason.EMPTY_NAME);
            } else {
                nonBlankString(entry.getValue(), "keys.bindings." + entry.getKey())
                        .ifPresent(keystroke -> bindings.put(entry.getKey(), keystroke));
            }
        }
        return bindings;
    }

    /** 비어 있지 않은 문자열이면 그 값. 없으면 비어 있고, 다른 형식이면 경고하고 비어 있다. */
    Optional<String> nonBlankString(JsonNode node, String key) {
        if (node == null) {
            return Optional.empty();
        }
        if (node.isString() && !node.stringValue().isBlank()) {
            return Optional.of(node.stringValue());
        }
        ignore(key, IgnoredReason.NOT_NON_EMPTY_STRING);
        return Optional.empty();
    }

    private void ignore(String key, IgnoredReason reason) {
        warnings.add(new ConfigWarning.IgnoredValue(key, reason));
    }
}
