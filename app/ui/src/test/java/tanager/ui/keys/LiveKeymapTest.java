package tanager.ui.keys;

import static java.awt.event.InputEvent.ALT_DOWN_MASK;
import static java.awt.event.InputEvent.CTRL_DOWN_MASK;
import static java.awt.event.InputEvent.SHIFT_DOWN_MASK;
import static org.assertj.core.api.Assertions.assertThat;

import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.out.AppConfig;
import tanager.application.port.out.KeyConfig;
import tanager.domain.layout.Orientation;

/** 실행 중 설정이 바뀌면 다시 시작하지 않고 새 키를 쓴다(FR-CFG-03, #183). */
class LiveKeymapTest {
    private static final int CTRL_SHIFT = CTRL_DOWN_MASK | SHIFT_DOWN_MASK;
    private static final int ALT_SHIFT = ALT_DOWN_MASK | SHIFT_DOWN_MASK;

    private static AppConfig configWith(Map<String, String> bindings) {
        AppConfig base = AppConfig.defaults();
        return new AppConfig(
                base.defaultProfile(),
                base.profiles(),
                base.openPlacement(),
                base.appearance(),
                base.terminal(),
                base.history(),
                base.monitor(),
                new KeyConfig(Optional.empty(), bindings));
    }

    @Test
    @DisplayName("설정을 받기 전에는 기본 키다")
    void startsWithDefaults() {
        // given
        LiveKeymap keys = new LiveKeymap();

        // then
        assertThat(keys.current().bindings()).isEqualTo(Keymap.defaults().bindings());
    }

    @Test
    @DisplayName("설정이 바뀌면 새 키가 바로 동작하고 문제를 돌려준다")
    void appliesNewConfig() {
        // given
        LiveKeymap keys = new LiveKeymap();

        // when
        List<KeyProblem> problems =
                keys.apply(configWith(Map.of("pane.splitRight", "ctrl shift D", "pane.zoom", "ctrl C")));

        // then
        assertThat(keys.current().actionFor(KeyEvent.VK_D, CTRL_SHIFT))
                .contains(new LayoutAction.Split(Orientation.ROW));
        assertThat(keys.current().actionFor(KeyEvent.VK_EQUALS, ALT_SHIFT)).isEmpty();
        assertThat(problems).containsExactly(new KeyProblem.Reserved("pane.zoom", "Ctrl+C"));
    }

    @Test
    @DisplayName("재정의를 지운 설정이 오면 기본 키로 돌아간다 — 앞의 재정의가 남지 않는다")
    void laterConfigReplacesEarlier() {
        // given
        LiveKeymap keys = new LiveKeymap();
        keys.apply(configWith(Map.of("pane.splitRight", "ctrl shift D")));

        // when
        List<KeyProblem> problems = keys.apply(AppConfig.defaults());

        // then
        assertThat(problems).isEmpty();
        assertThat(keys.current().actionFor(KeyEvent.VK_D, CTRL_SHIFT)).isEmpty();
        assertThat(keys.current().actionFor(KeyEvent.VK_EQUALS, ALT_SHIFT))
                .contains(new LayoutAction.Split(Orientation.ROW));
    }
}
