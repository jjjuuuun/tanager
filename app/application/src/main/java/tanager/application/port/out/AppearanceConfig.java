package tanager.application.port.out;

import java.util.Objects;

/**
 * 외관 설정(config {@code appearance}). 모서리 반경({@code cornerRadius})은 테마 토큰이 정하므로 읽지 않는다.
 *
 * @param theme 테마
 * @param animations 전환 효과
 */
public record AppearanceConfig(ThemeMode theme, AnimationMode animations) {
    private static final AppearanceConfig DEFAULTS = new AppearanceConfig(ThemeMode.SYSTEM, AnimationMode.AUTO);

    public AppearanceConfig {
        Objects.requireNonNull(theme, "theme");
        Objects.requireNonNull(animations, "animations");
    }

    /** SDD §9.3 의 기본값: SYSTEM · AUTO. */
    public static AppearanceConfig defaults() {
        return DEFAULTS;
    }
}
