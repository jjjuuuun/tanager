package tanager.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.domain.session.ProfileId;

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
        assertThat(new ConfigError.WouldOverwrite(ConfigError.Risk.CORRUPT_FILE, "손상").describe())
                .isNotBlank()
                .contains("손상");
        assertThat(new ConfigError.WriteFailed("디스크").describe()).isNotBlank().contains("디스크");
    }

    @Test
    @DisplayName("프로파일 밖의 항목 기본값은 SDD §9.3 그대로다")
    void sectionDefaultsFollowSdd() {
        // when
        AppConfig defaults = AppConfig.defaults();

        // then
        assertThat(defaults.openPlacement()).isEqualTo(OpenPlacement.RIGHT);
        assertThat(defaults.appearance()).isEqualTo(new AppearanceConfig(ThemeMode.SYSTEM, AnimationMode.AUTO));
        assertThat(defaults.terminal()).isEqualTo(new TerminalConfig("D2Coding", 14, 1.2, 10_000));
        assertThat(defaults.history()).isEqualTo(new HistoryConfig(2000, List.of("/tmp/**", "**/node_modules/**")));
        assertThat(defaults.monitor())
                .isEqualTo(new MonitorConfig(true, 2, EnumSet.allOf(MonitorItem.class), 0.90, 10, 0.85, 0.90));
        assertThat(defaults.keys()).isEqualTo(new KeyConfig(Optional.empty(), Map.of()));
    }

    @Test
    @DisplayName("터미널 설정은 범위 밖 크기 · 줄 높이 · 스크롤백과 빈 글꼴을 거부하고 경계값은 받는다")
    void terminalConfigEnforcesRanges() {
        assertThat(new TerminalConfig("a", 8, 1.0, 0).size()).isEqualTo(8);
        assertThat(new TerminalConfig("a", 32, 2.0, 100_000).size()).isEqualTo(32);
        assertThatThrownBy(() -> new TerminalConfig("a", 7, 1.2, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TerminalConfig("a", 33, 1.2, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TerminalConfig("a", 14, 0.9, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TerminalConfig("a", 14, Double.NaN, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TerminalConfig("a", 14, 1.2, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TerminalConfig("a", 14, 1.2, 100_001))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TerminalConfig(" ", 14, 1.2, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("이력 설정은 한도 100~100000 밖과 빈 glob 을 거부한다")
    void historyConfigEnforcesRanges() {
        assertThat(new HistoryConfig(100, List.of()).maxEntries()).isEqualTo(100);
        assertThatThrownBy(() -> new HistoryConfig(99, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HistoryConfig(100_001, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HistoryConfig(2000, List.of(" "))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("리소스 설정은 간격 1~10, 임계값 0 초과 1 이하, 지속 시간 1 이상만 받는다")
    void monitorConfigEnforcesRanges() {
        Set<MonitorItem> all = EnumSet.allOf(MonitorItem.class);
        assertThat(new MonitorConfig(true, 10, all, 1.0, 1, 1.0, 1.0).intervalSec())
                .isEqualTo(10);
        assertThatThrownBy(() -> new MonitorConfig(true, 0, all, 0.9, 10, 0.9, 0.9))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MonitorConfig(true, 11, all, 0.9, 10, 0.9, 0.9))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MonitorConfig(true, 2, all, 0.0, 10, 0.9, 0.9))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MonitorConfig(true, 2, all, 0.9, 10, 1.01, 0.9))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MonitorConfig(true, 2, all, 0.9, 10, 0.9, -0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MonitorConfig(true, 2, all, 0.9, 0, 0.9, 0.9))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("리소스 항목은 열거 순서로 돌고 바꿀 수 없으며, 비어 있어도 된다")
    void monitorItemsAreOrderedAndImmutable() {
        // when
        MonitorConfig config = new MonitorConfig(
                true, 2, Set.of(MonitorItem.WSL, MonitorItem.CPU, MonitorItem.DISK), 0.9, 10, 0.9, 0.9);

        // then
        assertThat(config.items()).containsExactly(MonitorItem.CPU, MonitorItem.DISK, MonitorItem.WSL);
        assertThatThrownBy(() -> config.items().add(MonitorItem.MEMORY))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(new MonitorConfig(true, 2, Set.of(), 0.9, 10, 0.9, 0.9).items())
                .isEmpty();
    }

    @Test
    @DisplayName("키 설정은 빈 prefix 와 빈 동작 id · 키를 거부한다")
    void keyConfigRejectsBlanks() {
        assertThatThrownBy(() -> new KeyConfig(Optional.of(" "), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KeyConfig(Optional.empty(), Map.of(" ", "alt R")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KeyConfig(Optional.empty(), Map.of("pane.splitRight", "")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("withDefaultProfile 은 기본 프로파일만 바꾸고, withSavedKeysOf 는 저장소가 쓰는 항목만 가져온다")
    void copyMethodsChangeOnlyTheirKeys() {
        // given
        AppConfig dark = new AppConfig(
                Optional.empty(),
                Map.of(),
                OpenPlacement.DOWN,
                new AppearanceConfig(ThemeMode.DARK, AnimationMode.OFF),
                TerminalConfig.defaults(),
                HistoryConfig.defaults(),
                MonitorConfig.defaults(),
                KeyConfig.defaults());
        AppConfig saved = new AppConfig(
                Optional.of(new ProfileId("cmd")),
                Map.of(new ProfileId("cmd"), new ProfileSettings(Optional.of("C:\\a"))));

        // when
        AppConfig remembered = dark.withDefaultProfile(new ProfileId("cmd"));
        AppConfig merged = dark.withSavedKeysOf(saved);

        // then
        assertThat(remembered.defaultProfile()).contains(new ProfileId("cmd"));
        assertThat(remembered.appearance()).isEqualTo(dark.appearance());
        assertThat(remembered.openPlacement()).isEqualTo(OpenPlacement.DOWN);
        assertThat(merged.defaultProfile()).isEqualTo(saved.defaultProfile());
        assertThat(merged.profiles()).isEqualTo(saved.profiles());
        assertThat(merged.appearance()).isEqualTo(dark.appearance());
        assertThat(merged.openPlacement()).isEqualTo(OpenPlacement.DOWN);
    }

    @Test
    @DisplayName("범위 밖 경고는 키 · 찾은 값 · 쓴 값을 설명에 담는다")
    void outOfRangeDescribesItself() {
        assertThat(new ConfigWarning.OutOfRange("terminal.size", "200", "32").describe())
                .contains("terminal.size")
                .contains("200")
                .contains("32");
    }
}
