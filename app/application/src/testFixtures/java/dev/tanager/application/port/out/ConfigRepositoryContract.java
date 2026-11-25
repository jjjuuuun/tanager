package dev.tanager.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tanager.domain.session.ProfileId;
import dev.tanager.domain.shared.Result;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** {@link ConfigRepository} 계약(ADR-0009). 메모리 Fake 와 JSON 파일 구현이 함께 상속한다. */
@Tag("contract")
public abstract class ConfigRepositoryContract {

    /** 비어 있는(아직 저장한 적 없는) 저장소. 같은 테스트 안에서는 같은 인스턴스를 돌려준다. */
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
        assertThat(result.value()).contains(new LoadedConfig(AppConfig.defaults(), java.util.List.of()));
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
        assertThat(loaded.value()).contains(new LoadedConfig(sample(), java.util.List.of()));
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
        assertThat(repository.load().value()).contains(new LoadedConfig(AppConfig.defaults(), java.util.List.of()));
    }
}
