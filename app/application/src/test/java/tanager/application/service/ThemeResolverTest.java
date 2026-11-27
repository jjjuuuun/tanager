package tanager.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tanager.application.port.in.ResolvedAppearance;
import tanager.application.port.out.AnimationMode;
import tanager.application.port.out.AppearanceConfig;
import tanager.application.port.out.SystemAppearance;
import tanager.application.port.out.Theme;
import tanager.application.port.out.ThemeMode;

/** 설정 모드와 OS 값으로 지금 쓸 테마 · 움직임을 정한다(FR-UI-01 · FR-UI-04, SDD §6.7). */
class ThemeResolverTest {

    private static SystemAppearance os(String theme, boolean animations) {
        Optional<Theme> value = theme.equals("EMPTY") ? Optional.empty() : Optional.of(Theme.valueOf(theme));
        return new SystemAppearance(value, animations);
    }

    @ParameterizedTest(name = "({0}, {1}) → {2}")
    @CsvSource({
        "SYSTEM, LIGHT, LIGHT",
        "SYSTEM, DARK, DARK",
        "SYSTEM, EMPTY, DARK",
        "DARK, LIGHT, DARK",
        "DARK, EMPTY, DARK",
        "LIGHT, DARK, LIGHT",
        "LIGHT, EMPTY, LIGHT"
    })
    @DisplayName("테마: SYSTEM 은 OS 값(읽지 못하면 다크), DARK · LIGHT 는 OS 와 상관없다")
    void resolvesTheme(ThemeMode mode, String osTheme, Theme expected) {
        // when
        Theme theme = ThemeResolver.resolve(mode, os(osTheme, true));

        // then
        assertThat(theme).isEqualTo(expected);
    }

    @ParameterizedTest(name = "({0}, {1}) → {2}")
    @CsvSource({
        "AUTO, false, false",
        "AUTO, true, true",
        "ON, false, true",
        "ON, true, true",
        "OFF, true, false",
        "OFF, false, false"
    })
    @DisplayName("움직임: AUTO 는 OS 값, ON · OFF 는 OS 와 상관없이 강제한다")
    void resolvesAnimations(AnimationMode mode, boolean osAnimations, boolean expected) {
        // when
        boolean animations = ThemeResolver.animations(mode, os("EMPTY", osAnimations));

        // then
        assertThat(animations).isEqualTo(expected);
    }

    @Test
    @DisplayName("외관 설정 전체와 OS 값을 합쳐 한 값으로 만든다")
    void resolvesWholeConfig() {
        // given
        AppearanceConfig config = new AppearanceConfig(ThemeMode.SYSTEM, AnimationMode.OFF);

        // when
        ResolvedAppearance resolved = ThemeResolver.resolve(config, os("LIGHT", true));

        // then
        assertThat(resolved).isEqualTo(new ResolvedAppearance(Theme.LIGHT, false));
    }

    @Test
    @DisplayName("기본 설정과 OS 값을 못 읽은 기본값이면 다크 · 움직임 켬이다")
    void defaultsAreDarkWithAnimations() {
        // when
        ResolvedAppearance resolved = ThemeResolver.resolve(AppearanceConfig.defaults(), SystemAppearance.DEFAULT);

        // then
        assertThat(resolved).isEqualTo(new ResolvedAppearance(Theme.DARK, true));
    }
}
