package tanager.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tanager.application.port.out.AppConfig;
import tanager.application.port.out.ConfigError;
import tanager.application.port.out.ConfigWarning;
import tanager.application.port.out.LoadedConfig;
import tanager.application.port.out.ProfileSettings;
import tanager.domain.session.ProfileId;
import tanager.domain.shared.Result;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class JsonConfigRepositoryTest {
    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("config.json");
    }

    private JsonConfigRepository repository() {
        return new JsonConfigRepository(file());
    }

    private void write(String text) throws IOException {
        Files.writeString(file(), text, StandardCharsets.UTF_8);
    }

    private static AppConfig configWith(String id, String startDir) {
        return new AppConfig(Optional.empty(), Map.of(new ProfileId(id), new ProfileSettings(Optional.of(startDir))));
    }

    // ---- 읽기 ----

    @Test
    @DisplayName("파일이 없으면 기본값이고 파일을 만들지 않는다")
    void missingFileLoadsDefaultsWithoutCreatingIt() {
        // when
        Result<LoadedConfig, ConfigError> result = repository().load();

        // then
        assertThat(result.value()).contains(new LoadedConfig(AppConfig.defaults(), List.of()));
        assertThat(file()).doesNotExist();
    }

    @Test
    @DisplayName("주석과 UTF-8 BOM 이 있어도 읽는다")
    void readsCommentsAndBom() throws IOException {
        // given
        write("\uFEFF// 내 설정\n{ /* 기본 */ \"defaultProfile\": \"wsl:A\", // 끝\n"
                + "  \"profiles\": { \"wsl:A\": { \"startDir\": \"/home/me\" } } }\n");

        // when
        Result<LoadedConfig, ConfigError> result = repository().load();

        // then
        assertThat(result.value())
                .contains(new LoadedConfig(
                        new AppConfig(
                                Optional.of(new ProfileId("wsl:A")),
                                Map.of(new ProfileId("wsl:A"), new ProfileSettings(Optional.of("/home/me")))),
                        List.of()));
    }

    @Test
    @DisplayName("끝 쉼표가 있어도 읽는다")
    void readsTrailingComma() throws IOException {
        // given
        write("{ \"defaultProfile\": \"cmd\", \"profiles\": { \"cmd\": { \"startDir\": \"C:\\\\a\", }, }, }");

        // when
        LoadedConfig loaded = repository().load().value().orElseThrow();

        // then
        assertThat(loaded.config().defaultProfile()).contains(new ProfileId("cmd"));
        assertThat(loaded.config().startDirOf(new ProfileId("cmd"))).contains("C:\\a");
        assertThat(loaded.warnings()).isEmpty();
    }

    @Test
    @DisplayName("schemaVersion 이 없으면 1 로 보고 경고하지 않는다")
    void missingSchemaVersionIsVersionOne() throws IOException {
        // given
        write("{ \"defaultProfile\": \"cmd\" }");

        // when
        LoadedConfig loaded = repository().load().value().orElseThrow();

        // then
        assertThat(loaded.warnings()).isEmpty();
        assertThat(loaded.config().defaultProfile()).contains(new ProfileId("cmd"));
    }

    @Test
    @DisplayName("형식이 틀린 키는 그 키만 무시하고 경고하며 나머지는 읽는다")
    void wrongTypedKeyIsIgnoredWithWarning() throws IOException {
        // given
        write("{ \"defaultProfile\": 5, \"profiles\": { \"cmd\": { \"startDir\": \"C:\\\\x\" } } }");

        // when
        LoadedConfig loaded = repository().load().value().orElseThrow();

        // then
        assertThat(loaded.config().defaultProfile()).isEmpty();
        assertThat(loaded.config().startDirOf(new ProfileId("cmd"))).contains("C:\\x");
        assertThat(loaded.warnings()).hasSize(1);
        assertThat(loaded.warnings().getFirst()).isInstanceOf(ConfigWarning.IgnoredValue.class);
        assertThat(((ConfigWarning.IgnoredValue) loaded.warnings().getFirst()).key())
                .isEqualTo("defaultProfile");
    }

    @Test
    @DisplayName("빈 startDir 는 무시하고 경고한다")
    void blankStartDirIsIgnoredWithWarning() throws IOException {
        // given
        write("{ \"profiles\": { \"cmd\": { \"startDir\": \"\" } } }");

        // when
        LoadedConfig loaded = repository().load().value().orElseThrow();

        // then
        assertThat(loaded.config().startDirOf(new ProfileId("cmd"))).isEmpty();
        assertThat(loaded.warnings()).hasSize(1);
        assertThat(loaded.warnings().getFirst()).isInstanceOf(ConfigWarning.IgnoredValue.class);
    }

    @Test
    @DisplayName("상위 버전 파일은 읽을 수 있는 만큼 읽고 NewerVersion 경고를 낸다")
    void newerVersionIsReadWithWarning() throws IOException {
        // given
        write("{ \"schemaVersion\": 2, \"defaultProfile\": \"cmd\" }");

        // when
        LoadedConfig loaded = repository().load().value().orElseThrow();

        // then
        assertThat(loaded.config().defaultProfile()).contains(new ProfileId("cmd"));
        assertThat(loaded.warnings()).containsExactly(new ConfigWarning.NewerVersion(2));
    }

    @Test
    @DisplayName("문법이 틀린 JSON 은 Corrupt 이고 파일을 바꾸지 않는다")
    void syntaxErrorIsCorruptAndFileUntouched() throws IOException {
        // given
        write("{ \"defaultProfile\": ");
        byte[] before = Files.readAllBytes(file());

        // when
        Result<LoadedConfig, ConfigError> result = repository().load();

        // then
        assertThat(result.error().orElseThrow()).isInstanceOf(ConfigError.Corrupt.class);
        assertThat(Files.readAllBytes(file())).isEqualTo(before);
    }

    @Test
    @DisplayName("Corrupt 의 설명에는 오류가 난 줄:열이 있다(언어와 무관한 형식, #137)")
    void corruptDetailHasLocation() throws IOException {
        // given
        write("{\n  \"defaultProfile\": ,\n}");

        // when
        ConfigError error = repository().load().error().orElseThrow();

        // then
        assertThat(error).isInstanceOf(ConfigError.Corrupt.class);
        assertThat(((ConfigError.Corrupt) error).detail()).containsPattern("\\(2:\\d+\\)$");
    }

    @Test
    @DisplayName("빈 파일 · 공백뿐인 파일은 없는 파일처럼 기본값이고 경고가 없다")
    void blankFileLoadsDefaults() throws IOException {
        for (String text : List.of("", "  \n\t\r\n", "\uFEFF")) {
            // given
            write(text);

            // when
            Result<LoadedConfig, ConfigError> result = repository().load();

            // then
            assertThat(result.value()).as("내용 %s", text).contains(new LoadedConfig(AppConfig.defaults(), List.of()));
        }
    }

    @Test
    @DisplayName("빈 파일 위에는 정상으로 저장한다")
    void saveOverBlankFile() throws IOException {
        // given
        write("  \n");

        // when
        Result<AppConfig, ConfigError> saved = repository().save(configWith("cmd", "C:\\x"));

        // then
        assertThat(saved.isOk()).isTrue();
        assertThat(repository().load().value()).contains(new LoadedConfig(configWith("cmd", "C:\\x"), List.of()));
    }

    @Test
    @DisplayName("루트가 객체가 아니면 Corrupt 다")
    void nonObjectRootIsCorrupt() throws IOException {
        // given
        write("[1, 2]");

        // when / then
        assertThat(repository().load().error().orElseThrow()).isInstanceOf(ConfigError.Corrupt.class);
    }

    @Test
    @DisplayName("대상이 디렉터리라 읽지 못하면 Unreadable 이다")
    void directoryTargetIsUnreadable() throws IOException {
        // given
        Files.createDirectories(file());

        // when / then
        assertThat(repository().load().error().orElseThrow()).isInstanceOf(ConfigError.Unreadable.class);
    }

    // ---- 쓰기 ----

    @Test
    @DisplayName("손상된 파일 위에는 쓰지 않고 WouldOverwrite 를 돌려주며 바이트가 그대로다")
    void saveRefusesToOverwriteCorruptFile() throws IOException {
        // given
        write("{ 손상된 파일");
        byte[] before = Files.readAllBytes(file());

        // when
        Result<AppConfig, ConfigError> result = repository().save(configWith("cmd", "C:\\x"));

        // then
        assertThat(result.error().orElseThrow()).isInstanceOf(ConfigError.WouldOverwrite.class);
        assertThat(Files.readAllBytes(file())).isEqualTo(before);
    }

    @Test
    @DisplayName("상위 버전 파일 위에는 쓰지 않고 WouldOverwrite 를 돌려주며 바이트가 그대로다")
    void saveRefusesToOverwriteNewerVersion() throws IOException {
        // given
        write("{ \"schemaVersion\": 2, \"future\": true }");
        byte[] before = Files.readAllBytes(file());

        // when
        Result<AppConfig, ConfigError> result = repository().save(configWith("cmd", "C:\\x"));

        // then
        assertThat(result.error().orElseThrow()).isInstanceOf(ConfigError.WouldOverwrite.class);
        assertThat(Files.readAllBytes(file())).isEqualTo(before);
    }

    @Test
    @DisplayName("schemaVersion 을 정수로 해석할 수 없으면(문자열 숫자 · int 범위 밖) 저장하지 않는다")
    void saveRefusesUninterpretableSchemaVersion() throws IOException {
        for (String version : List.of("\"2\"", "99999999999", "1.5")) {
            // given
            write("{ \"schemaVersion\": " + version + " }");
            byte[] before = Files.readAllBytes(file());

            // when
            Result<AppConfig, ConfigError> result = repository().save(configWith("cmd", "C:\\x"));

            // then
            assertThat(result.error()).as("schemaVersion %s", version).isPresent();
            assertThat(result.error().orElseThrow()).isInstanceOf(ConfigError.WouldOverwrite.class);
            assertThat(Files.readAllBytes(file())).isEqualTo(before);
        }
    }

    @Test
    @DisplayName("저장해도 모르는 키는 그대로 보존한다")
    void saveKeepsUnknownKeys() throws IOException {
        // given
        write("{ \"appearance\": { \"theme\": \"dark\" },"
                + " \"profiles\": { \"cmd\": { \"foo\": 1, \"startDir\": \"C:\\\\a\" } } }");

        // when
        Result<AppConfig, ConfigError> saved = repository().save(configWith("cmd", "C:\\b"));

        // then
        assertThat(saved.isOk()).isTrue();
        String text = Files.readString(file(), StandardCharsets.UTF_8);
        assertThat(text).contains("\"appearance\"").contains("\"theme\"").contains("\"dark\"");
        assertThat(text).contains("\"foo\"");
        assertThat(repository().load().value().orElseThrow().config().startDirOf(new ProfileId("cmd")))
                .contains("C:\\b");
    }

    @Test
    @DisplayName("저장하면 schemaVersion 1 을 기록한다")
    void saveWritesSchemaVersion() throws IOException {
        // when
        repository().save(AppConfig.defaults());

        // then
        assertThat(Files.readString(file(), StandardCharsets.UTF_8)).containsPattern("\"schemaVersion\"\\s*:\\s*1");
    }

    @Test
    @DisplayName("저장이 끝나면 임시 파일이 남지 않는다")
    void saveLeavesNoTmpFile() {
        // when
        repository().save(configWith("cmd", "C:\\x"));

        // then
        assertThat(dir.resolve("config.json.tmp")).doesNotExist();
        assertThat(file()).exists();
    }

    @Test
    @DisplayName("대상이 디렉터리라 기존 파일을 읽지 못하면 쓰지 않고 WriteFailed 이며 원본은 그대로다")
    void saveFailsWhenTargetCannotBeRead() throws IOException {
        // given
        Files.createDirectories(file());
        Files.writeString(file().resolve("keep.txt"), "x");

        // when
        Result<AppConfig, ConfigError> result = repository().save(configWith("cmd", "C:\\x"));

        // then
        assertThat(result.error().orElseThrow()).isInstanceOf(ConfigError.WriteFailed.class);
        assertThat(file()).isDirectory();
        assertThat(file().resolve("keep.txt")).exists();
    }

    @Test
    @DisplayName("임시 파일을 쓰지 못하면 WriteFailed 이고 원본 바이트는 그대로다")
    void saveFailsWhenTmpCannotBeWritten() throws IOException {
        // given
        write("{ \"defaultProfile\": \"cmd\" }");
        byte[] before = Files.readAllBytes(file());
        Path tmp = dir.resolve("config.json.tmp");
        Files.createDirectories(tmp);
        Files.writeString(tmp.resolve("keep.txt"), "x");

        // when
        Result<AppConfig, ConfigError> result = repository().save(configWith("cmd", "C:\\x"));

        // then
        assertThat(result.error().orElseThrow()).isInstanceOf(ConfigError.WriteFailed.class);
        assertThat(Files.readAllBytes(file())).isEqualTo(before);
        assertThat(tmp.resolve("keep.txt")).exists();
    }

    // ---- 병합(아는 키만 바꾼다) ----

    private JsonNode savedTree() throws IOException {
        return JsonMapper.builder().build().readTree(Files.readString(file(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("설정에서 빠진 프로파일이 startDir 만 가졌으면 항목을 지운다")
    void removedProfileWithOnlyStartDirIsDeleted() throws IOException {
        // given
        write("{ \"profiles\": { \"cmd\": { \"startDir\": \"C:\\\\a\" },"
                + " \"powershell\": { \"startDir\": \"C:\\\\b\" } } }");

        // when
        repository().save(configWith("powershell", "C:\\b"));

        // then
        JsonNode profiles = savedTree().get("profiles");
        assertThat(profiles.has("cmd")).isFalse();
        assertThat(profiles.get("powershell").get("startDir").stringValue()).isEqualTo("C:\\b");
    }

    @Test
    @DisplayName("설정에서 빠진 프로파일에 다른 키가 있으면 startDir 만 지운다")
    void removedProfileKeepsOtherKeys() throws IOException {
        // given
        write("{ \"profiles\": { \"cmd\": { \"startDir\": \"C:\\\\a\", \"foo\": 1 } } }");

        // when
        repository().save(AppConfig.defaults());

        // then
        JsonNode cmd = savedTree().get("profiles").get("cmd");
        assertThat(cmd.has("startDir")).isFalse();
        assertThat(cmd.get("foo").intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("객체가 아닌 프로파일 값은 설정에 없으면 그대로 둔다")
    void nonObjectProfileValueIsKept() throws IOException {
        // given
        write("{ \"profiles\": { \"cmd\": 5 } }");

        // when
        repository().save(AppConfig.defaults());

        // then
        assertThat(savedTree().get("profiles").get("cmd").intValue()).isEqualTo(5);
    }

    @Test
    @DisplayName("기본 프로파일이 비면 defaultProfile 키를 지운다")
    void emptyDefaultProfileRemovesKey() throws IOException {
        // given
        write("{ \"defaultProfile\": \"cmd\" }");

        // when
        repository().save(AppConfig.defaults());

        // then
        assertThat(savedTree().has("defaultProfile")).isFalse();
    }

    @Test
    @DisplayName("남은 프로파일이 없으면 profiles 키를 지운다")
    void emptyProfilesRemovesKey() throws IOException {
        // given
        write("{ \"profiles\": { \"cmd\": { \"startDir\": \"C:\\\\a\" } } }");

        // when
        repository().save(AppConfig.defaults());

        // then
        assertThat(savedTree().has("profiles")).isFalse();
    }

    @Test
    @DisplayName("부모 폴더가 없으면 만들어서 저장한다")
    void saveCreatesParentDirectories() {
        // given
        Path nested = dir.resolve("a").resolve("b").resolve("config.json");

        // when
        Result<AppConfig, ConfigError> result = new JsonConfigRepository(nested).save(configWith("cmd", "C:\\x"));

        // then
        assertThat(result.isOk()).isTrue();
        assertThat(nested).exists();
    }

    // ---- 속성 ----

    @Provide
    Arbitrary<String> startDirs() {
        return Arbitraries.strings()
                .withCharRange(' ', '\uD7FF')
                .withChars('"', '\\', '/', '\n', '\t')
                .ofMinLength(1)
                .ofMaxLength(40)
                .filter(s -> !s.isBlank());
    }

    @Provide
    Arbitrary<String> profileIds() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars(':', '-', '한', '글', '"', '\\')
                .ofMinLength(1)
                .ofMaxLength(15);
    }

    @Property(tries = 30)
    @Label("어떤 시작 경로 · 프로파일 id 도 저장 후 읽으면 그대로다")
    void arbitraryValuesRoundTrip(@ForAll("profileIds") String id, @ForAll("startDirs") String startDir)
            throws IOException {
        // given
        Path tmp = Files.createTempDirectory("tanager-config");
        try {
            JsonConfigRepository repository = new JsonConfigRepository(tmp.resolve("config.json"));
            AppConfig config = configWith(id, startDir);

            // when
            repository.save(config);

            // then
            assertThat(repository.load().value()).contains(new LoadedConfig(config, List.of()));
        } finally {
            try (var walk = Files.walk(tmp)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }
}
