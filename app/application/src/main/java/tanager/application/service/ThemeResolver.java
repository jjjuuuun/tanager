package tanager.application.service;

import tanager.application.port.in.ResolvedAppearance;
import tanager.application.port.out.AnimationMode;
import tanager.application.port.out.AppearanceConfig;
import tanager.application.port.out.SystemAppearance;
import tanager.application.port.out.Theme;
import tanager.application.port.out.ThemeMode;

/** 설정 모드와 OS 값으로 지금 쓸 테마 · 움직임을 정하는 순수 함수(SDD §6.7). */
final class ThemeResolver {
    /** OS 테마를 읽지 못했을 때 시스템 모드가 쓰는 테마 — M2 부터의 기본. */
    static final Theme FALLBACK = Theme.DARK;

    private ThemeResolver() {}

    /** SYSTEM 은 OS 값(읽지 못하면 {@link #FALLBACK}), DARK · LIGHT 는 그대로. */
    static Theme resolve(ThemeMode mode, SystemAppearance os) {
        return switch (mode) {
            case SYSTEM -> os.theme().orElse(FALLBACK);
            case DARK -> Theme.DARK;
            case LIGHT -> Theme.LIGHT;
        };
    }

    /** AUTO 는 OS 값, ON · OFF 는 강제. */
    static boolean animations(AnimationMode mode, SystemAppearance os) {
        return switch (mode) {
            case AUTO -> os.animations();
            case ON -> true;
            case OFF -> false;
        };
    }

    /** 외관 설정 전체와 OS 값을 합친다. */
    static ResolvedAppearance resolve(AppearanceConfig config, SystemAppearance os) {
        return new ResolvedAppearance(resolve(config.theme(), os), animations(config.animations(), os));
    }
}
