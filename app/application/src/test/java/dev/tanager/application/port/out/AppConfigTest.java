package dev.tanager.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tanager.domain.session.ProfileId;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AppConfigTest {

    @Test
    @DisplayName("기본 설정은 기본 프로파일도 프로파일별 설정도 없다")
    void defaultsAreEmpty() {
        assertThat(AppConfig.defaults()).isEqualTo(new AppConfig(Optional.empty(), Map.of()));
    }

    @Test
    @DisplayName("startDirOf 는 프로파일에 지정한 시작 경로를 돌려주고 없으면 비어 있다")
    void startDirOfReturnsConfiguredValue() {
        // given
        ProfileId ps = new ProfileId("powershell");
        AppConfig config = new AppConfig(
                Optional.empty(),
                Map.of(
                        ps,
                        new ProfileSettings(Optional.of("C:\\work")),
                        new ProfileId("cmd"),
                        new ProfileSettings(Optional.empty())));

        // then
        assertThat(config.startDirOf(ps)).contains("C:\\work");
        assertThat(config.startDirOf(new ProfileId("cmd"))).isEmpty();
        assertThat(config.startDirOf(new ProfileId("unknown"))).isEmpty();
    }

    @Test
    @DisplayName("ProfileSettings 는 빈 문자열 시작 경로를 거부한다")
    void profileSettingsRejectsBlankStartDir() {
        assertThatThrownBy(() -> new ProfileSettings(Optional.of(""))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProfileSettings(Optional.of("  "))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("설정 오류와 경고는 사용자에게 보여 줄 설명을 가진다")
    void errorsDescribeThemselves() {
        assertThat(new ConfigError.Corrupt("줄 3").describe()).isNotBlank().contains("줄 3");
        assertThat(new ConfigError.Unreadable("권한").describe()).isNotBlank().contains("권한");
        assertThat(new ConfigError.WouldOverwrite("손상").describe()).isNotBlank().contains("손상");
        assertThat(new ConfigError.WriteFailed("디스크").describe()).isNotBlank().contains("디스크");
    }
}
