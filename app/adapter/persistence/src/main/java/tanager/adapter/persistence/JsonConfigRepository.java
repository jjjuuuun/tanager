package tanager.adapter.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tanager.application.port.out.AppConfig;
import tanager.application.port.out.ConfigError;
import tanager.application.port.out.ConfigRepository;
import tanager.application.port.out.ConfigWarning;
import tanager.application.port.out.LoadedConfig;
import tanager.application.port.out.OpenPlacement;
import tanager.application.port.out.ProfileSettings;
import tanager.domain.session.ProfileId;
import tanager.domain.shared.Result;
import tools.jackson.core.JacksonException;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * config.json(SDD §9.3) 을 읽고 쓴다(ADR-0009). 사람이 고치는 파일이라 읽기는 관대하고(주석 · 끝 쉼표 · BOM 허용, 빈 파일은
 * 없는 파일처럼, 형식이 틀린 키는 그 키만 무시) 쓰기는 보수적이다(디스크 파일을 다시 읽어 모르는 키를 보존하고, 손상 · 상위 버전 ·
 * 해석할 수 없는 schemaVersion 의 파일은 덮어쓰지 않으며, 임시 파일에 쓴 뒤 원자적으로 바꾼다). 쓰는 키는 schemaVersion · defaultProfile ·
 * profiles.&lt;id&gt;.startDir 뿐이다 — 나머지 항목은 읽기만 한다.
 */
public final class JsonConfigRepository implements ConfigRepository {
    private static final int SCHEMA_VERSION = 1;

    private static final String KEY_SCHEMA_VERSION = "schemaVersion";
    private static final String KEY_DEFAULT_PROFILE = "defaultProfile";
    private static final String KEY_PROFILES = "profiles";
    private static final String KEY_START_DIR = "startDir";
    private static final String KEY_OPEN_PLACEMENT = "openPlacement";
    // 아래는 읽기만 한다(ConfigSections)
    private static final String KEY_APPEARANCE = "appearance";
    private static final String KEY_TERMINAL = "terminal";
    private static final String KEY_HISTORY = "history";
    private static final String KEY_MONITOR = "monitor";
    private static final String KEY_KEYS = "keys";
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final byte[] NEW_FILE =
            ("{\n  \"" + KEY_SCHEMA_VERSION + "\": " + SCHEMA_VERSION + "\n}\n").getBytes(StandardCharsets.UTF_8);

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS, JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();
    private static final ObjectWriter WRITER = JsonFiles.prettyWriter(MAPPER);

    private final Path file;

    /** {@code file} 은 보통 {@code PlatformDirs.appData()/config.json}. 만들 때 파일을 건드리지 않는다. */
    public JsonConfigRepository(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    @Override
    public Result<LoadedConfig, ConfigError> load() {
        return readDisk().flatMap(disk -> switch (disk) {
            case Disk.Missing _ -> Result.ok(new LoadedConfig(AppConfig.defaults(), List.of()));
            case Disk.Parsed parsed -> Result.ok(interpret(parsed.root()));
        });
    }

    @Override
    public Result<Path, ConfigError> ensureFile() {
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return Result.ok(file);
        }
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            createNew();
            return Result.ok(file);
        } catch (IOException e) {
            return Result.err(new ConfigError.WriteFailed(file + ": " + e));
        }
    }

    // CREATE_NEW — 확인과 쓰기 사이에 사용자가 만든 파일은 덮어쓰지 않는다
    private void createNew() throws IOException {
        try {
            Files.write(file, NEW_FILE, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException e) {
            // 그사이 생겼다 — 그 파일을 연다
        }
    }

    @Override
    public Result<AppConfig, ConfigError> save(AppConfig config) {
        Objects.requireNonNull(config, "config");
        Result<Disk, ConfigError> disk = readDisk();
        if (disk instanceof Result.Err<Disk, ConfigError>(ConfigError error)) {
            return Result.err(refusal(error));
        }
        ObjectNode root = switch (disk.value().orElseThrow()) {
            case Disk.Missing _ -> MAPPER.createObjectNode();
            case Disk.Parsed parsed -> parsed.root();
        };
        Optional<ConfigError> refused = versionRefusal(root);
        if (refused.isPresent()) {
            return Result.err(refused.get());
        }
        merge(root, config);
        return write(root).map(_ -> config);
    }

    // ---- 읽기 ----

    private sealed interface Disk {
        record Missing() implements Disk {}

        record Parsed(ObjectNode root) implements Disk {}
    }

    private Result<Disk, ConfigError> readDisk() {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            return Result.ok(new Disk.Missing());
        } catch (IOException e) {
            return Result.err(new ConfigError.Unreadable(file + ": " + e));
        }
        int offset = startsWithBom(bytes) ? UTF8_BOM.length : 0;
        // 빈 파일은 사용자가 내용을 지운 것이다 — 지킬 내용이 없으니 없는 파일과 같게 다룬다
        if (isBlank(bytes, offset)) {
            return Result.ok(new Disk.Missing());
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(bytes, offset, bytes.length - offset);
        } catch (JacksonException e) {
            return Result.err(new ConfigError.Corrupt(describe(e)));
        }
        if (root instanceof ObjectNode object) {
            return Result.ok(new Disk.Parsed(object));
        }
        return Result.err(new ConfigError.Corrupt("최상위 값이 객체가 아니다"));
    }

    private static boolean startsWithBom(byte[] bytes) {
        return bytes.length >= UTF8_BOM.length
                && bytes[0] == UTF8_BOM[0]
                && bytes[1] == UTF8_BOM[1]
                && bytes[2] == UTF8_BOM[2];
    }

    private static boolean isBlank(byte[] bytes, int offset) {
        for (int i = offset; i < bytes.length; i++) {
            byte b = bytes[i];
            if (b != ' ' && b != '\t' && b != '\r' && b != '\n') {
                return false;
            }
        }
        return true;
    }

    private static String describe(JacksonException e) {
        String message = Optional.ofNullable(e.getOriginalMessage())
                .filter(m -> !m.isBlank())
                .orElse("JSON 문법 오류");
        TokenStreamLocation location = e.getLocation();
        if (location == null || location.getLineNr() < 1) {
            return message;
        }
        return message + " (" + location.getLineNr() + ":" + location.getColumnNr() + ")";
    }

    private static LoadedConfig interpret(ObjectNode root) {
        List<ConfigWarning> warnings = new ArrayList<>();
        Optional<Integer> version = schemaVersion(root);
        if (version.isEmpty()) {
            warnings.add(new ConfigWarning.IgnoredValue(
                    KEY_SCHEMA_VERSION, ConfigWarning.IgnoredReason.NOT_POSITIVE_INTEGER));
        } else if (version.get() > SCHEMA_VERSION) {
            warnings.add(new ConfigWarning.NewerVersion(version.get()));
        }
        ConfigSections sections = new ConfigSections(warnings);
        Optional<ProfileId> defaultProfile = sections.nonBlankString(root.get(KEY_DEFAULT_PROFILE), KEY_DEFAULT_PROFILE)
                .map(ProfileId::new);
        Map<ProfileId, ProfileSettings> profiles = profiles(root.get(KEY_PROFILES), sections, warnings);
        OpenPlacement placement = openPlacement(root.get(KEY_OPEN_PLACEMENT), sections, warnings);
        return new LoadedConfig(
                new AppConfig(
                        defaultProfile,
                        profiles,
                        placement,
                        sections.appearance(root.get(KEY_APPEARANCE)),
                        sections.terminal(root.get(KEY_TERMINAL)),
                        sections.history(root.get(KEY_HISTORY)),
                        sections.monitor(root.get(KEY_MONITOR)),
                        sections.keys(root.get(KEY_KEYS))),
                warnings);
    }

    // 없으면 RIGHT. 앱은 이 키를 쓰지 않는다 — 파일 값은 merge 가 보존한다.
    private static OpenPlacement openPlacement(JsonNode node, ConfigSections sections, List<ConfigWarning> warnings) {
        if (node == null) {
            return OpenPlacement.RIGHT;
        }
        Optional<String> value = sections.nonBlankString(node, KEY_OPEN_PLACEMENT);
        if (value.isEmpty()) {
            return OpenPlacement.RIGHT;
        }
        switch (value.get()) {
            case "RIGHT" -> {
                return OpenPlacement.RIGHT;
            }
            case "DOWN" -> {
                return OpenPlacement.DOWN;
            }
            case "NEW_TAB" -> {
                return OpenPlacement.NEW_TAB;
            }
            default ->
                warnings.add(
                        new ConfigWarning.IgnoredValue(KEY_OPEN_PLACEMENT, ConfigWarning.IgnoredReason.UNKNOWN_VALUE));
        }
        return OpenPlacement.RIGHT;
    }

    // 없으면 1 로 본다. 1 이상의 정수가 아니면 비어 있다.
    private static Optional<Integer> schemaVersion(ObjectNode root) {
        JsonNode version = root.get(KEY_SCHEMA_VERSION);
        if (version == null) {
            return Optional.of(SCHEMA_VERSION);
        }
        if (version.isInt() && version.intValue() >= 1) {
            return Optional.of(version.intValue());
        }
        return Optional.empty();
    }

    private static Map<ProfileId, ProfileSettings> profiles(
            JsonNode node, ConfigSections sections, List<ConfigWarning> warnings) {
        Map<ProfileId, ProfileSettings> profiles = new HashMap<>();
        if (node == null) {
            return profiles;
        }
        if (!(node instanceof ObjectNode object)) {
            warnings.add(new ConfigWarning.IgnoredValue(KEY_PROFILES, ConfigWarning.IgnoredReason.NOT_OBJECT));
            return profiles;
        }
        for (Map.Entry<String, JsonNode> entry : object.properties()) {
            String key = KEY_PROFILES + "." + entry.getKey();
            if (entry.getKey().isBlank()) {
                warnings.add(new ConfigWarning.IgnoredValue(key, ConfigWarning.IgnoredReason.EMPTY_PROFILE_ID));
            } else if (!(entry.getValue() instanceof ObjectNode settings)) {
                warnings.add(new ConfigWarning.IgnoredValue(key, ConfigWarning.IgnoredReason.NOT_OBJECT));
            } else {
                Optional<String> startDir =
                        sections.nonBlankString(settings.get(KEY_START_DIR), key + "." + KEY_START_DIR);
                profiles.put(new ProfileId(entry.getKey()), new ProfileSettings(startDir));
            }
        }
        return profiles;
    }

    // ---- 쓰기 ----

    // 덮어써도 되는 것은 schemaVersion 이 없거나 정수 1 일 때뿐이다. 해석할 수 없는 값도 사용자의 의도를 모르니 지킨다.
    private static Optional<ConfigError> versionRefusal(ObjectNode root) {
        JsonNode version = root.get(KEY_SCHEMA_VERSION);
        if (version == null || (version.isInt() && version.intValue() == SCHEMA_VERSION)) {
            return Optional.empty();
        }
        ConfigError.Risk risk =
                schemaVersion(root).filter(v -> v > SCHEMA_VERSION).isPresent()
                        ? ConfigError.Risk.NEWER_VERSION
                        : ConfigError.Risk.UNREADABLE_VERSION;
        return Optional.of(new ConfigError.WouldOverwrite(risk, "schemaVersion " + version));
    }

    // 읽지 못한 파일은 덮어쓰지 않는다. 손상은 사용자의 내용을 지키려는 거부이고, 그 밖은 쓰기 실패다.
    private static ConfigError refusal(ConfigError readError) {
        return switch (readError) {
            case ConfigError.Corrupt corrupt ->
                new ConfigError.WouldOverwrite(ConfigError.Risk.CORRUPT_FILE, corrupt.detail());
            case ConfigError.Unreadable unreadable -> new ConfigError.WriteFailed(unreadable.detail());
            case ConfigError.WouldOverwrite w -> w;
            case ConfigError.WriteFailed w -> w;
        };
    }

    // 아는 키만 바꾼다. 설정에서 빠진 프로파일은 startDir 만 지우고, 남은 키가 없을 때만 항목을 지운다.
    private static void merge(ObjectNode root, AppConfig config) {
        root.put(KEY_SCHEMA_VERSION, SCHEMA_VERSION);
        config.defaultProfile()
                .ifPresentOrElse(
                        id -> root.put(KEY_DEFAULT_PROFILE, id.value()), () -> root.remove(KEY_DEFAULT_PROFILE));
        ObjectNode profiles =
                root.get(KEY_PROFILES) instanceof ObjectNode existing ? existing : MAPPER.createObjectNode();
        for (String name : List.copyOf(profiles.propertyNames())) {
            boolean configured = !name.isBlank() && config.profiles().containsKey(new ProfileId(name));
            if (!configured && profiles.get(name) instanceof ObjectNode settings) {
                settings.remove(KEY_START_DIR);
                if (settings.isEmpty()) {
                    profiles.remove(name);
                }
            }
        }
        for (Map.Entry<ProfileId, ProfileSettings> entry : config.profiles().entrySet()) {
            String name = entry.getKey().value();
            ObjectNode settings =
                    profiles.get(name) instanceof ObjectNode existing ? existing : MAPPER.createObjectNode();
            entry.getValue()
                    .startDir()
                    .ifPresentOrElse(dir -> settings.put(KEY_START_DIR, dir), () -> settings.remove(KEY_START_DIR));
            profiles.set(name, settings);
        }
        if (profiles.isEmpty()) {
            root.remove(KEY_PROFILES);
        } else {
            root.set(KEY_PROFILES, profiles);
        }
    }

    private Result<Path, ConfigError> write(ObjectNode root) {
        try {
            JsonFiles.writeAtomically(file, WRITER.writeValueAsBytes(root));
            return Result.ok(file);
        } catch (IOException | JacksonException e) {
            return Result.err(new ConfigError.WriteFailed(file + ": " + e));
        }
    }
}
