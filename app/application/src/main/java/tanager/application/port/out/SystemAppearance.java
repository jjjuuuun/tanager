package tanager.application.port.out;

import java.util.Objects;
import java.util.Optional;

/**
 * OS 의 외관 설정(SDD §6.7).
 *
 * @param theme OS 의 앱 모드(다크/라이트, FR-UI-01). 읽지 못하면 비어 있다
 * @param animations OS 가 애니메이션을 허용하는가. 끄면 UI 전환은 즉시 최종 값이 된다(FR-UI-04)
 */
public record SystemAppearance(Optional<Theme> theme, boolean animations) {

    /** OS 값을 읽지 못했을 때 쓰는 기본값 — 테마는 모르고 애니메이션은 켠다. */
    public static final SystemAppearance DEFAULT = new SystemAppearance(Optional.empty(), true);

    public SystemAppearance {
        Objects.requireNonNull(theme, "theme");
    }
}
