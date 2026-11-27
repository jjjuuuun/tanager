package tanager.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tanager.application.port.out.AnimationMode;
import tanager.application.port.out.AppConfig;
import tanager.application.port.out.AppearanceConfig;
import tanager.application.port.out.ConfigWarning;
import tanager.application.port.out.ConfigWarning.IgnoredReason;
import tanager.application.port.out.HistoryConfig;
import tanager.application.port.out.KeyConfig;
import tanager.application.port.out.LoadedConfig;
import tanager.application.port.out.MonitorConfig;
import tanager.application.port.out.MonitorItem;
import tanager.application.port.out.OpenPlacement;
import tanager.application.port.out.ProfileSettings;
import tanager.application.port.out.TerminalConfig;
import tanager.application.port.out.ThemeMode;
import tanager.domain.session.ProfileId;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** config.json 의 appearance · terminal · history · monitor · keys 읽기(#182, SDD §9.3, ADR-0009). */
class JsonConfigSectionsTest {
    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("config.json");
    }

    private void write(String text) throws IOException {
        Files.writeString(file(), text, StandardCharsets.UTF_8);
    }

    private LoadedConfig load() {
        return new JsonConfigRepository(file()).load().value().orElseThrow();
    }

    private LoadedConfig load(String text) throws IOException {
        write(text);
        return load();
    }

    private static ConfigWarning ignored(String key, IgnoredReason reason) {
        return new ConfigWarning.IgnoredValue(key, reason);
    }

    @Test
    @DisplayName("SDD §9.3 예시 파일(주석 포함)을 읽으면 모든 값이 설정에 있고 경고가 없다")
    void readsSddExample() throws IOException {
        // given
        try (InputStream in = getClass().getResourceAsStream("/config/sdd-example.json")) {
            Files.write(file(), in.readAllBytes());
        }

        // when
        LoadedConfig loaded = load();

        // then
        assertThat(loaded.warnings()).isEmpty();
        assertThat(loaded.config())
                .isEqualTo(new AppConfig(
                        Optional.of(new ProfileId("wsl:Ubuntu-24.04")),
                        Map.of(
                                new ProfileId("powershell"), new ProfileSettings(Optional.of("C:\\work")),
                                new ProfileId("wsl:Ubuntu-24.04"), new ProfileSettings(Optional.of("~"))),
                        OpenPlacement.RIGHT,
                        new AppearanceConfig(ThemeMode.SYSTEM, AnimationMode.AUTO),
                        new TerminalConfig("D2Coding", 14, 1.2, 10_000),
                        new HistoryConfig(2000, List.of("/tmp/**", "**/node_modules/**")),
                        new MonitorConfig(true, 2, EnumSet.allOf(MonitorItem.class), 0.90, 10, 0.85, 0.90),
                        new KeyConfig(Optional.empty(), Map.of("pane.splitRight", "alt shift EQUALS"))));
    }

    @Test
    @DisplayName("기본값이 아닌 값을 모두 읽는다(테마 · 효과 · 항목 이름은 대소문자를 가리지 않는다)")
    void readsNonDefaultValues() throws IOException {
        // when
        LoadedConfig loaded = load("""
                { "appearance": { "theme": "dark", "animations": "Off" },
                  "terminal": { "font": "Consolas", "size": 20, "lineHeight": 1.5, "scrollback": 0 },
                  "history": { "maxEntries": 500, "exclude": [] },
                  "monitor": { "enabled": false, "intervalSec": 10, "items": ["wsl", "CPU", "CPU"],
                               "thresholds": { "cpu": 1, "cpuSustainSec": 1, "memory": 0.5, "disk": 0.75 } },
                  "keys": { "prefix": "ctrl B", "bindings": {} } }
                """);

        // then
        assertThat(loaded.warnings()).isEmpty();
        AppConfig config = loaded.config();
        assertThat(config.appearance()).isEqualTo(new AppearanceConfig(ThemeMode.DARK, AnimationMode.OFF));
        assertThat(config.terminal()).isEqualTo(new TerminalConfig("Consolas", 20, 1.5, 0));
        assertThat(config.history()).isEqualTo(new HistoryConfig(500, List.of()));
        assertThat(config.monitor())
                .isEqualTo(new MonitorConfig(false, 10, Set.of(MonitorItem.CPU, MonitorItem.WSL), 1.0, 1, 0.5, 0.75));
        assertThat(config.keys()).isEqualTo(new KeyConfig(Optional.of("ctrl B"), Map.of()));
    }

    @Test
    @DisplayName("빠진 항목 · 빠진 키는 기본값이고 경고가 없다")
    void missingKeysAreDefaults() throws IOException {
        // when
        LoadedConfig loaded = load("{ \"terminal\": { \"size\": 16 }, \"monitor\": { \"thresholds\": {} } }");

        // then
        assertThat(loaded.warnings()).isEmpty();
        assertThat(loaded.config().terminal()).isEqualTo(new TerminalConfig("D2Coding", 16, 1.2, 10_000));
        assertThat(loaded.config().monitor()).isEqualTo(MonitorConfig.defaults());
        assertThat(loaded.config().appearance()).isEqualTo(AppearanceConfig.defaults());
    }

    @Test
    @DisplayName("terminal.size 200 은 32 로 고정하고 OutOfRange 경고를 낸다")
    void sizeAboveRangeIsClamped() throws IOException {
        // when
        LoadedConfig loaded = load("{ \"terminal\": { \"size\": 200 } }");

        // then
        assertThat(loaded.config().terminal().size()).isEqualTo(32);
        assertThat(loaded.warnings()).containsExactly(new ConfigWarning.OutOfRange("terminal.size", "200", "32"));
    }

    @Test
    @DisplayName("범위 밖 숫자는 가까운 경계로 고정한다 — int 를 넘는 수와 음수도")
    void outOfRangeNumbersAreClamped() throws IOException {
        // when
        LoadedConfig loaded = load("""
                { "terminal": { "size": 1, "lineHeight": 3, "scrollback": 99999999999 },
                  "history": { "maxEntries": -5 },
                  "monitor": { "intervalSec": 0, "thresholds": { "cpu": 1.5, "cpuSustainSec": 0 } } }
                """);

        // then
        assertThat(loaded.config().terminal()).isEqualTo(new TerminalConfig("D2Coding", 8, 2.0, 100_000));
        assertThat(loaded.config().history().maxEntries()).isEqualTo(100);
        assertThat(loaded.config().monitor().intervalSec()).isEqualTo(1);
        assertThat(loaded.config().monitor().cpu()).isEqualTo(1.0);
        assertThat(loaded.config().monitor().cpuSustainSec()).isEqualTo(1);
        assertThat(loaded.warnings())
                .containsExactlyInAnyOrder(
                        new ConfigWarning.OutOfRange("terminal.size", "1", "8"),
                        new ConfigWarning.OutOfRange("terminal.lineHeight", "3", "2.0"),
                        new ConfigWarning.OutOfRange("terminal.scrollback", "99999999999", "100000"),
                        new ConfigWarning.OutOfRange("history.maxEntries", "-5", "100"),
                        new ConfigWarning.OutOfRange("monitor.intervalSec", "0", "1"),
                        new ConfigWarning.OutOfRange("monitor.thresholds.cpu", "1.5", "1.0"),
                        new ConfigWarning.OutOfRange("monitor.thresholds.cpuSustainSec", "0", "1"));
    }

    @Test
    @DisplayName("임계값이 0 이하면 경계가 없어 기본값을 쓰고 OutOfRange 경고를 낸다")
    void nonPositiveThresholdUsesDefault() throws IOException {
        // when
        LoadedConfig loaded = load("{ \"monitor\": { \"thresholds\": { \"memory\": 0, \"disk\": -1 } } }");

        // then
        assertThat(loaded.config().monitor().memory()).isEqualTo(0.85);
        assertThat(loaded.config().monitor().disk()).isEqualTo(0.90);
        assertThat(loaded.warnings())
                .containsExactly(
                        new ConfigWarning.OutOfRange("monitor.thresholds.memory", "0", "0.85"),
                        new ConfigWarning.OutOfRange("monitor.thresholds.disk", "-1", "0.9"));
    }

    @Test
    @DisplayName("appearance.theme BLUE 는 SYSTEM 이고 경고하며 다른 키는 그대로 읽는다")
    void unknownThemeFallsBackKeepingOthers() throws IOException {
        // when
        LoadedConfig loaded = load(
                "{ \"appearance\": { \"theme\": \"BLUE\", \"animations\": \"OFF\" }, \"terminal\": { \"size\": 18 } }");

        // then
        assertThat(loaded.config().appearance()).isEqualTo(new AppearanceConfig(ThemeMode.SYSTEM, AnimationMode.OFF));
        assertThat(loaded.config().terminal().size()).isEqualTo(18);
        assertThat(loaded.warnings()).containsExactly(ignored("appearance.theme", IgnoredReason.UNKNOWN_VALUE));
    }

    @Test
    @DisplayName("monitor.items [CPU, GPU] 는 CPU 만 남기고 모르는 항목을 경고한다")
    void unknownMonitorItemIsDropped() throws IOException {
        // when
        LoadedConfig loaded = load("{ \"monitor\": { \"items\": [\"CPU\", \"GPU\"] } }");

        // then
        assertThat(loaded.config().monitor().items()).containsExactly(MonitorItem.CPU);
        assertThat(loaded.warnings()).containsExactly(ignored("monitor.items[1]", IgnoredReason.UNKNOWN_VALUE));
    }

    @Test
    @DisplayName("형식이 틀린 키는 그 키만 기본값으로 두고 이유를 경고한다")
    void wrongTypesFallBackPerKey() throws IOException {
        // when
        LoadedConfig loaded = load("""
                { "appearance": 3,
                  "terminal": { "font": "", "size": 14.5, "lineHeight": "big", "scrollback": true },
                  "history": { "maxEntries": "many", "exclude": "/tmp/**" },
                  "monitor": { "enabled": "yes", "items": "CPU", "thresholds": [] },
                  "keys": { "prefix": 5, "bindings": [] } }
                """);

        // then
        assertThat(loaded.config().appearance()).isEqualTo(AppearanceConfig.defaults());
        assertThat(loaded.config().terminal()).isEqualTo(TerminalConfig.defaults());
        assertThat(loaded.config().history()).isEqualTo(HistoryConfig.defaults());
        assertThat(loaded.config().monitor()).isEqualTo(MonitorConfig.defaults());
        assertThat(loaded.config().keys()).isEqualTo(KeyConfig.defaults());
        assertThat(loaded.warnings())
                .containsExactlyInAnyOrder(
                        ignored("appearance", IgnoredReason.NOT_OBJECT),
                        ignored("terminal.font", IgnoredReason.NOT_NON_EMPTY_STRING),
                        ignored("terminal.size", IgnoredReason.NOT_INTEGER),
                        ignored("terminal.lineHeight", IgnoredReason.NOT_NUMBER),
                        ignored("terminal.scrollback", IgnoredReason.NOT_INTEGER),
                        ignored("history.maxEntries", IgnoredReason.NOT_INTEGER),
                        ignored("history.exclude", IgnoredReason.NOT_ARRAY),
                        ignored("monitor.enabled", IgnoredReason.NOT_BOOLEAN),
                        ignored("monitor.items", IgnoredReason.NOT_ARRAY),
                        ignored("monitor.thresholds", IgnoredReason.NOT_OBJECT),
                        ignored("keys.prefix", IgnoredReason.NOT_STRING),
                        ignored("keys.bindings", IgnoredReason.NOT_OBJECT));
    }

    @Test
    @DisplayName("glob 으로 읽을 수 없는 history.exclude 항목(상대 경로)은 그것만 빼고 경고한다")
    void invalidGlobIsDropped() throws IOException {
        // when
        LoadedConfig loaded = load("""
                { "history": { "exclude": ["tmp/**", "/tmp/**", "*.log"] } }
                """);

        // then
        assertThat(loaded.config().history().exclude()).containsExactly("/tmp/**");
        assertThat(loaded.warnings())
                .containsExactly(
                        ignored("history.exclude[0]", IgnoredReason.INVALID_GLOB),
                        ignored("history.exclude[2]", IgnoredReason.INVALID_GLOB));
    }

    @Test
    @DisplayName("배열 · 객체 안의 틀린 항목은 그 항목만 빼고 경고한다")
    void badElementsAreDropped() throws IOException {
        // when
        LoadedConfig loaded = load("""
                { "history": { "exclude": ["/a/**", 3, " ", "/b/**"] },
                  "monitor": { "items": ["DISK", 7] },
                  "keys": { "bindings": { "pane.close": "alt W", "": "alt X", "pane.zoom": "  ", "pane.swap": 1 } } }
                """);

        // then
        assertThat(loaded.config().history().exclude()).containsExactly("/a/**", "/b/**");
        assertThat(loaded.config().monitor().items()).containsExactly(MonitorItem.DISK);
        assertThat(loaded.config().keys().bindings()).isEqualTo(Map.of("pane.close", "alt W"));
        assertThat(loaded.warnings())
                .containsExactlyInAnyOrder(
                        ignored("history.exclude[1]", IgnoredReason.NOT_NON_EMPTY_STRING),
                        ignored("history.exclude[2]", IgnoredReason.NOT_NON_EMPTY_STRING),
                        ignored("monitor.items[1]", IgnoredReason.NOT_NON_EMPTY_STRING),
                        ignored("keys.bindings", IgnoredReason.EMPTY_NAME),
                        ignored("keys.bindings.pane.zoom", IgnoredReason.NOT_NON_EMPTY_STRING),
                        ignored("keys.bindings.pane.swap", IgnoredReason.NOT_NON_EMPTY_STRING));
    }

    @Test
    @DisplayName("keys.prefix 가 null · 빈 문자열이면 prefix 모드가 꺼지고 경고가 없다")
    void nullOrEmptyPrefixMeansOff() throws IOException {
        for (String prefix : List.of("null", "\"\"", "\"  \"")) {
            // when
            LoadedConfig loaded = load("{ \"keys\": { \"prefix\": " + prefix + " } }");

            // then
            assertThat(loaded.config().keys().prefix()).as(prefix).isEmpty();
            assertThat(loaded.warnings()).as(prefix).isEmpty();
        }
    }

    @Test
    @DisplayName("appearance.cornerRadius 는 읽지 않는다 — 형식이 틀려도 경고가 없다")
    void cornerRadiusIsNotRead() throws IOException {
        // when
        LoadedConfig loaded = load("{ \"appearance\": { \"cornerRadius\": \"round\" } }");

        // then
        assertThat(loaded.warnings()).isEmpty();
        assertThat(loaded.config().appearance()).isEqualTo(AppearanceConfig.defaults());
    }

    @Test
    @DisplayName("모르는 키가 있는 파일에 기본 셸을 저장해도 모르는 키와 이번에 더한 키가 모두 보존된다")
    void saveKeepsUnknownAndNewSections() throws IOException {
        // given
        try (InputStream in = getClass().getResourceAsStream("/config/sdd-example.json")) {
            Files.write(file(), in.readAllBytes());
        }
        write(Files.readString(file(), StandardCharsets.UTF_8)
                .replace("\"size\": 14", "\"size\": 20")
                .replace("\"theme\": \"SYSTEM\"", "\"theme\": \"DARK\""));
        JsonConfigRepository repository = new JsonConfigRepository(file());
        AppConfig before = repository.load().value().orElseThrow().config();

        // when
        var saved = repository.save(before.withDefaultProfile(new ProfileId("cmd")));

        // then
        assertThat(saved.isOk()).isTrue();
        JsonNode tree = JsonMapper.builder().build().readTree(Files.readString(file(), StandardCharsets.UTF_8));
        assertThat(tree.get("defaultProfile").stringValue()).isEqualTo("cmd");
        assertThat(tree.get("toolkit").get("providers").get(0).stringValue()).isEqualTo("claude-code");
        assertThat(tree.get("layout").get("minPane").get("cols").intValue()).isEqualTo(20);
        assertThat(tree.get("appearance").get("cornerRadius").intValue()).isEqualTo(8);
        assertThat(tree.get("terminal").get("size").intValue()).isEqualTo(20);
        assertThat(tree.get("monitor").get("thresholds").get("memory").doubleValue())
                .isEqualTo(0.85);
        assertThat(tree.get("keys").get("bindings").get("pane.splitRight").stringValue())
                .isEqualTo("alt shift EQUALS");
        AppConfig after = repository.load().value().orElseThrow().config();
        assertThat(after).isEqualTo(before.withDefaultProfile(new ProfileId("cmd")));
    }

    @Test
    @DisplayName("save 는 넘긴 설정의 외관 · 터미널 값을 파일에 쓰지 않는다(주석을 지키려고 쓰는 키를 늘리지 않는다)")
    void saveDoesNotWriteNewSections() throws IOException {
        // given
        write("{ \"terminal\": { \"size\": 16 } }");
        JsonConfigRepository repository = new JsonConfigRepository(file());
        AppConfig other = new AppConfig(
                Optional.of(new ProfileId("cmd")),
                Map.of(),
                OpenPlacement.DOWN,
                new AppearanceConfig(ThemeMode.LIGHT, AnimationMode.ON),
                new TerminalConfig("Consolas", 24, 1.0, 5),
                HistoryConfig.defaults(),
                MonitorConfig.defaults(),
                KeyConfig.defaults());

        // when
        repository.save(other);

        // then
        AppConfig after = repository.load().value().orElseThrow().config();
        assertThat(after.terminal().size()).isEqualTo(16);
        assertThat(after.appearance()).isEqualTo(AppearanceConfig.defaults());
        JsonNode tree = JsonMapper.builder().build().readTree(Files.readString(file(), StandardCharsets.UTF_8));
        assertThat(tree.has("appearance")).isFalse();
        assertThat(tree.has("openPlacement")).isFalse();
    }

    // ---- ensureFile ----

    @Test
    @DisplayName("ensureFile 은 config.json 이 없으면 schemaVersion 1 만 담아 만들고 그 경로를 돌려준다")
    void ensureFileCreatesMinimalFile() throws IOException {
        // given
        Path nested = dir.resolve("a").resolve("config.json");

        // when
        var result = new JsonConfigRepository(nested).ensureFile();

        // then
        assertThat(result.value()).contains(nested);
        JsonNode tree = JsonMapper.builder().build().readTree(Files.readString(nested, StandardCharsets.UTF_8));
        assertThat(tree.propertyNames()).containsExactly("schemaVersion");
        assertThat(tree.get("schemaVersion").intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("ensureFile 은 있는 파일(빈 파일 · 손상 파일 포함)의 바이트를 바꾸지 않는다")
    void ensureFileKeepsExistingBytes() throws IOException {
        for (String text : List.of("", "// 내 설정\n{ \"terminal\": { \"size\": 16 } }", "{ 손상")) {
            // given
            write(text);
            byte[] before = Files.readAllBytes(file());

            // when
            var result = new JsonConfigRepository(file()).ensureFile();

            // then
            assertThat(result.value()).as(text).contains(file());
            assertThat(Files.readAllBytes(file())).as(text).isEqualTo(before);
        }
    }

    @Test
    @DisplayName("ensureFile 이 파일을 만들지 못하면 WriteFailed 다")
    void ensureFileFailsWhenParentIsAFile() throws IOException {
        // given
        Files.writeString(dir.resolve("blocker"), "x");
        Path impossible = dir.resolve("blocker").resolve("config.json");

        // when
        var result = new JsonConfigRepository(impossible).ensureFile();

        // then
        assertThat(result.error().orElseThrow())
                .isInstanceOf(tanager.application.port.out.ConfigError.WriteFailed.class);
    }
}
