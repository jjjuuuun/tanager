package tanager.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tanager.domain.session.ProfileId;
import tanager.domain.shared.Result;

/** {@link ConfigRepository} 계약(ADR-0009). 메모리 Fake 와 JSON 파일 구현이 함께 상속한다. */
@Tag("contract")
public abstract class ConfigRepositoryContract {

    /** 비어 있는(아직 저장한 적 없는) 저장소. 같은 테스트 안에서 여러 번 불러도 같은 저장소(같은 저장 위치)를 돌려준다. */
    protected abstract ConfigRepository repository();

    private static AppConfig sample() {
        return new AppConfig(
                Optional.of(new ProfileId("wsl:Ubuntu")),
                Map.of(
                        new ProfileId("powershell"), new ProfileSettings(Optional.of("C:\\작업\\프로젝트")),
                        new ProfileId("wsl:Ubuntu"), new ProfileSettings(Optional.of("/home/me/한글")),
                        new ProfileId("cmd"), new ProfileSettings(Optional.empty())));
    }

    @Test
    @DisplayName("빈 저장소를 읽으면 기본값이고 경고가 없다")
    public void loadFromEmptyStoreReturnsDefaults() {
        // when
        Result<LoadedConfig, ConfigError> result = repository().load();

        // then
        assertThat(result.value()).contains(new LoadedConfig(AppConfig.defaults(), List.of()));
    }

    @Test
    @DisplayName("저장한 설정을 다시 읽으면 한글 경로까지 그대로다")
    public void saveThenLoadRoundTrips() {
        // given
        ConfigRepository repository = repository();

        // when
        Result<AppConfig, ConfigError> saved = repository.save(sample());
        Result<LoadedConfig, ConfigError> loaded = repository.load();

        // then
        assertThat(saved.value()).contains(sample());
        assertThat(loaded.value()).contains(new LoadedConfig(sample(), List.of()));
    }

    @Test
    @DisplayName("두 번 저장하면 나중 값이 이긴다")
    public void laterSaveWins() {
        // given
        ConfigRepository repository = repository();
        repository.save(sample());

        // when
        repository.save(AppConfig.defaults());

        // then
        assertThat(repository.load().value()).contains(new LoadedConfig(AppConfig.defaults(), List.of()));
    }

    /** 저장소가 쓰지 않는 항목을 모두 기본값이 아니게 바꾼 설정. */
    private static AppConfig withEveryOtherSection(AppConfig base) {
        return new AppConfig(
                base.defaultProfile(),
                base.profiles(),
                OpenPlacement.DOWN,
                new AppearanceConfig(ThemeMode.DARK, AnimationMode.OFF),
                new TerminalConfig("Consolas", 20, 1.5, 500),
                new HistoryConfig(300, List.of("/x/**")),
                new MonitorConfig(false, 5, java.util.Set.of(MonitorItem.CPU), 0.5, 3, 0.5, 0.5),
                new KeyConfig(Optional.of("ctrl B"), Map.of("pane.splitRight", "alt R")));
    }

    @Test
    @DisplayName("save 는 기본 프로파일 · 시작 경로만 쓰고 나머지 항목은 저장소의 값을 그대로 둔다(ADR-0009)")
    public void saveWritesOnlyProfileKeys() {
        // given
        ConfigRepository repository = repository();

        // when
        Result<AppConfig, ConfigError> saved = repository.save(withEveryOtherSection(sample()));

        // then
        assertThat(saved.value()).contains(withEveryOtherSection(sample()));
        assertThat(repository.load().value()).contains(new LoadedConfig(sample(), List.of()));
    }

    @Test
    @DisplayName("ensureFile 은 없던 설정 파일을 만들고, 그 파일은 기본값으로 읽히며 경고가 없다")
    public void ensureFileCreatesDefaultFile() {
        // given
        ConfigRepository repository = repository();

        // when
        Result<Path, ConfigError> file = repository.ensureFile();

        // then
        assertThat(file.isOk()).isTrue();
        assertThat(repository.load().value()).contains(new LoadedConfig(AppConfig.defaults(), List.of()));
    }

    @Test
    @DisplayName("ensureFile 은 이미 있는 설정을 바꾸지 않고, 여러 번 불러도 같은 위치다")
    public void ensureFileKeepsExistingConfig() {
        // given
        ConfigRepository repository = repository();
        repository.save(sample());

        // when
        Path first = repository.ensureFile().value().orElseThrow();
        Path second = repository.ensureFile().value().orElseThrow();

        // then
        assertThat(second).isEqualTo(first);
        assertThat(repository.load().value()).contains(new LoadedConfig(sample(), List.of()));
    }
}
