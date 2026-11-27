package tanager.ui.motion;

import java.time.Duration;
import java.util.Objects;
import javax.swing.UIManager;

/**
 * 전환 효과 설정(FR-UI-04): 테마 토큰의 전환 시간과 OS 의 '애니메이션 효과' 설정. 시간은 코드에 두지 않고 테마 파일({@value
 * #DURATION_KEY}, ms)에서 읽는다.
 *
 * @param duration 전환 하나의 길이. 음수는 0 으로 본다
 * @param enabled OS 가 애니메이션을 허용하는가
 */
public record Motion(Duration duration, boolean enabled) {
    /** 테마 파일의 전환 시간 키(ms). */
    public static final String DURATION_KEY = "Tanager.motion.duration";

    public Motion {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            duration = Duration.ZERO;
        }
    }

    /** 설치된 테마의 전환 시간과 OS 설정. 토큰이 없으면 시간 0(즉시 전환)이다. EDT 에서 부른다. */
    public static Motion fromTheme(boolean enabled) {
        return new Motion(Duration.ofMillis(UIManager.getInt(DURATION_KEY)), enabled);
    }

    /** 움직임 없이 바로 최종 값으로 가야 하는가 — OS 가 껐거나 시간이 0. */
    public boolean instant() {
        return !enabled || duration.isZero();
    }
}
