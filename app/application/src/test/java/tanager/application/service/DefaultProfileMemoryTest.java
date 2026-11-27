package tanager.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tanager.application.port.in.Remembered;
import tanager.application.port.out.AnimationMode;
import tanager.application.port.out.AppConfig;
import tanager.application.port.out.AppearanceConfig;
import tanager.application.port.out.ConfigError;
import tanager.application.port.out.HistoryConfig;
import tanager.application.port.out.InMemoryConfigRepository;
import tanager.application.port.out.KeyConfig;
import tanager.application.port.out.LoadedConfig;
import tanager.application.port.out.MonitorConfig;
import tanager.application.port.out.OpenPlacement;
import tanager.application.port.out.ProfileSettings;
import tanager.application.port.out.TerminalConfig;
import tanager.application.port.out.ThemeMode;
import tanager.domain.session.ProfileId;
import tanager.domain.shared.Result;

class DefaultProfileMemoryTest {
    private static final ProfileId WSL_A = new ProfileId("wsl:A");
    private static final ProfileId WSL_B = new ProfileId("wsl:B");
    private static final Map<ProfileId, ProfileSettings> PROFILES =
            Map.of(new ProfileId("powershell"), new ProfileSettings(Optional.of("C:\\work")));

    private final InMemoryConfigRepository config = new InMemoryConfigRepository();
    private final DefaultProfileMemory memory = new DefaultProfileMemory(config);

    private void loads(Optional<ProfileId> defaultProfile) {
        config.willLoad(Result.ok(new LoadedConfig(new AppConfig(defaultProfile, PROFILES), List.of())));
    }

    @Test
    @DisplayName("기본 셸이 없으면 저장하고 프로파일별 설정은 그대로 둔다")
    void savesKeepingProfiles() {
        // given
        loads(Optional.empty());

        // when
        var result = memory.remember(WSL_B);

        // then
        assertThat(result.value()).contains(Remembered.SAVED);
        assertThat(config.saves()).containsExactly(new AppConfig(Optional.of(WSL_B), PROFILES));
    }

    @Test
    @DisplayName("다른 기본 셸이 있으면 새 값으로 바꿔 쓴다")
    void overwritesDifferentDefault() {
        // given
        loads(Optional.of(WSL_A));

        // when
        var result = memory.remember(WSL_B);

        // then
        assertThat(result.value()).contains(Remembered.SAVED);
        assertThat(config.saves()).containsExactly(new AppConfig(Optional.of(WSL_B), PROFILES));
    }

    @Test
    @DisplayName("이미 같은 기본 셸이면 파일을 건드리지 않는다")
    void unchangedWhenSame() {
        // given
        loads(Optional.of(WSL_B));

        // when
        var result = memory.remember(WSL_B);

        // then
        assertThat(result.value()).contains(Remembered.UNCHANGED);
        assertThat(config.saves()).isEmpty();
    }

    static Stream<ConfigError> loadErrors() {
        return Stream.of(new ConfigError.Corrupt("x"), new ConfigError.Unreadable("y"));
    }

    @ParameterizedTest
    @MethodSource("loadErrors")
    @DisplayName("설정을 읽지 못하면 기본값으로 덮어쓰지 않고 그 오류를 돌려준다")
    void doesNotSaveWhenLoadFails(ConfigError error) {
        // given
        config.willFailLoad(error);

        // when
        var result = memory.remember(new ProfileId("cmd"));

        // then
        assertThat(result.error()).contains(error);
        assertThat(config.saves()).isEmpty();
    }

    static Stream<ConfigError> saveErrors() {
        return Stream.of(
                new ConfigError.WouldOverwrite(ConfigError.Risk.NEWER_VERSION, "schemaVersion 2"),
                new ConfigError.WriteFailed("disk"));
    }

    @ParameterizedTest
    @MethodSource("saveErrors")
    @DisplayName("저장이 실패하면 그 오류를 돌려준다")
    void returnsSaveError(ConfigError error) {
        // given
        config.willFailSave(error);

        // when
        var result = memory.remember(new ProfileId("cmd"));

        // then
        assertThat(result.error()).contains(error);
    }

    @Test
    @DisplayName("기억할 때 읽은 설정의 다른 항목(외관 등)을 그대로 넘긴다")
    void savesKeepingOtherSections() {
        // given
        AppConfig dark = new AppConfig(
                Optional.empty(),
                PROFILES,
                OpenPlacement.DOWN,
                new AppearanceConfig(ThemeMode.DARK, AnimationMode.OFF),
                TerminalConfig.defaults(),
                HistoryConfig.defaults(),
                MonitorConfig.defaults(),
                KeyConfig.defaults());
        config.willLoad(Result.ok(new LoadedConfig(dark, List.of())));

        // when
        memory.remember(WSL_B);

        // then
        assertThat(config.saves()).containsExactly(dark.withDefaultProfile(WSL_B));
    }
}
