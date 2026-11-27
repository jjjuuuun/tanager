package tanager.ui.motion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import javax.swing.LookAndFeel;
import javax.swing.UIManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.ui.theme.SoftDarkLaf;

class MotionTest {

    @Test
    @DisplayName("OS 에서 애니메이션을 끄면 즉시 전환이다")
    void disabledIsInstant() {
        // given
        Motion motion = new Motion(Duration.ofMillis(180), false);

        // then
        assertThat(motion.instant()).isTrue();
    }

    @Test
    @DisplayName("전환 시간이 0 이면 켜져 있어도 즉시 전환이다")
    void zeroDurationIsInstant() {
        // then
        assertThat(new Motion(Duration.ZERO, true).instant()).isTrue();
        assertThat(new Motion(Duration.ofMillis(180), true).instant()).isFalse();
    }

    @Test
    @DisplayName("음수 시간은 0 으로 본다")
    void negativeDurationIsZero() {
        // when
        Motion motion = new Motion(Duration.ofMillis(-5), true);

        // then
        assertThat(motion.duration()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("null 시간은 받지 않는다")
    void rejectsNullDuration() {
        assertThatNullPointerException().isThrownBy(() -> new Motion(null, true));
    }

    @Test
    @DisplayName("fromTheme 은 설치된 테마의 전환 시간 토큰을 읽는다")
    void fromThemeReadsToken() throws Exception {
        // given
        LookAndFeel previous = UIManager.getLookAndFeel();
        UIManager.setLookAndFeel(new SoftDarkLaf());
        try {
            // when
            Motion motion = Motion.fromTheme(true);

            // then
            assertThat(motion.duration()).isEqualTo(Duration.ofMillis(UIManager.getInt(Motion.DURATION_KEY)));
            assertThat(motion.duration()).isPositive();
            assertThat(motion.enabled()).isTrue();
        } finally {
            UIManager.setLookAndFeel(previous);
        }
    }
}
