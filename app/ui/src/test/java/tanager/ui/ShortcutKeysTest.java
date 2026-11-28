package tanager.ui;

import static java.awt.event.InputEvent.ALT_DOWN_MASK;
import static java.awt.event.InputEvent.CTRL_DOWN_MASK;
import static java.awt.event.InputEvent.SHIFT_DOWN_MASK;
import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.event.KeyEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.swing.JPanel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.out.KeyConfig;
import tanager.domain.layout.Orientation;
import tanager.ui.keys.KeyRoute;
import tanager.ui.keys.Keymap;
import tanager.ui.keys.LayoutAction;
import tanager.ui.keys.PrefixState;

/** 메인 창이 터미널보다 먼저 보는 키(#148 · #183)와 prefix 모드(#193): 키 하나가 셸로 갈지 · 삼킬지 · 동작일지. */
class ShortcutKeysTest {
    private static final Component SOURCE = new JPanel();
    private static final int ALT_SHIFT = ALT_DOWN_MASK | SHIFT_DOWN_MASK;

    private final ManualTime time = new ManualTime();
    private final List<Boolean> shown = new ArrayList<>();

    static Keymap withPrefix(String prefix) {
        return Keymap.resolve(Keymap.defaults(), new KeyConfig(Optional.of(prefix), Map.of()))
                .keymap();
    }

    static KeyEvent pressed(int keyCode, int modifiers, char typed) {
        return new KeyEvent(SOURCE, KeyEvent.KEY_PRESSED, 0, modifiers, keyCode, typed);
    }

    static KeyEvent typed(int modifiers, char typed) {
        return new KeyEvent(SOURCE, KeyEvent.KEY_TYPED, 0, modifiers, KeyEvent.VK_UNDEFINED, typed);
    }

    static KeyEvent released(int keyCode, int modifiers, char typed) {
        return new KeyEvent(SOURCE, KeyEvent.KEY_RELEASED, 0, modifiers, keyCode, typed);
    }

    private ShortcutKeys keys(Keymap keymap) {
        return new ShortcutKeys(keymap, time, time, shown::add);
    }

    // 누름 · 글자 · 뗌 세 이벤트의 판정
    private static List<KeyRoute> stroke(ShortcutKeys keys, int keyCode, int modifiers, char typed) {
        return List.of(
                keys.route(pressed(keyCode, modifiers, typed)),
                keys.route(typed(modifiers, typed)),
                keys.route(released(keyCode, modifiers, typed)));
    }

    private static final KeyRoute PASS = new KeyRoute.Pass();
    private static final KeyRoute SWALLOW = new KeyRoute.Swallow();

    @Test
    @DisplayName("prefix 가 없으면 Ctrl+B 는 누름 · 글자 모두 셸로 간다")
    void noPrefixPassesCtrlB() {
        // given
        ShortcutKeys keys = keys(Keymap.defaults());

        // when
        List<KeyRoute> routes = stroke(keys, KeyEvent.VK_B, CTRL_DOWN_MASK, '\u0002');

        // then
        assertThat(routes).containsExactly(PASS, PASS, PASS);
        assertThat(shown).isEmpty();
    }

    @Test
    @DisplayName("기본 키는 지금처럼 동작으로 판정하고 그 키가 만드는 글자도 삼킨다 — 뗀 다음 글자는 셸로 간다")
    void defaultKeysRunAndSwallowTheirCharacter() {
        // given
        ShortcutKeys keys = keys(withPrefix("ctrl B"));

        // when
        List<KeyRoute> routes = stroke(keys, KeyEvent.VK_EQUALS, ALT_SHIFT, '+');
        KeyRoute next = keys.route(typed(0, 'a'));

        // then
        assertThat(routes).containsExactly(new KeyRoute.Run(new LayoutAction.Split(Orientation.ROW)), SWALLOW, PASS);
        assertThat(next).isEqualTo(PASS);
    }

    @Test
    @DisplayName("Ctrl+B 다음 % 는 오른쪽 분할이고 그 사이 키는 모두 삼킨다 — 상태줄 PREFIX 가 켜졌다가 꺼진다")
    void prefixThenPercentSplits() {
        // given
        ShortcutKeys keys = keys(withPrefix("ctrl B"));

        // when
        List<KeyRoute> prefix = stroke(keys, KeyEvent.VK_B, CTRL_DOWN_MASK, '\u0002');
        List<Boolean> during = List.copyOf(shown);
        KeyRoute shift = keys.route(pressed(KeyEvent.VK_SHIFT, SHIFT_DOWN_MASK, KeyEvent.CHAR_UNDEFINED));
        List<KeyRoute> percent = stroke(keys, KeyEvent.VK_5, SHIFT_DOWN_MASK, '%');

        // then
        assertThat(prefix).containsExactly(SWALLOW, SWALLOW, PASS);
        assertThat(during).containsExactly(true);
        assertThat(shift).isEqualTo(PASS);
        assertThat(percent).containsExactly(SWALLOW, new KeyRoute.Run(new LayoutAction.Split(Orientation.ROW)), PASS);
        assertThat(shown).containsExactly(true, false);
    }

    @Test
    @DisplayName("prefix 모드 중의 기본 키(Alt+Shift+=)는 prefix 열로만 판정한다 — 열에 없으니 삼키고 끝낸다")
    void defaultKeyDuringPrefixIsSwallowed() {
        // given
        ShortcutKeys keys = keys(withPrefix("ctrl B"));
        stroke(keys, KeyEvent.VK_B, CTRL_DOWN_MASK, '\u0002');

        // when
        List<KeyRoute> routes = stroke(keys, KeyEvent.VK_EQUALS, ALT_SHIFT, '+');

        // then
        assertThat(routes).containsExactly(SWALLOW, SWALLOW, PASS);
        assertThat(shown).containsExactly(true, false);
    }

    @Test
    @DisplayName("Ctrl+B 두 번이면 두 번째는 누름 · 글자 모두 셸로 간다")
    void prefixTwicePassesSecond() {
        // given
        ShortcutKeys keys = keys(withPrefix("ctrl B"));
        stroke(keys, KeyEvent.VK_B, CTRL_DOWN_MASK, '\u0002');

        // when
        List<KeyRoute> second = stroke(keys, KeyEvent.VK_B, CTRL_DOWN_MASK, '\u0002');

        // then
        assertThat(second).containsExactly(PASS, PASS, PASS);
        assertThat(shown).containsExactly(true, false);
    }

    @Test
    @DisplayName("1.5초가 지나면 키를 누르지 않아도 PREFIX 표시가 사라지고 다음 키는 셸로 간다")
    void timeoutHidesIndicatorWithoutKey() {
        // given
        ShortcutKeys keys = keys(withPrefix("ctrl B"));
        stroke(keys, KeyEvent.VK_B, CTRL_DOWN_MASK, '\u0002');

        // when
        time.advance(PrefixState.TIMEOUT.minusMillis(1));
        List<Boolean> before = List.copyOf(shown);
        time.advance(Duration.ofMillis(1));
        List<KeyRoute> next = stroke(keys, KeyEvent.VK_5, SHIFT_DOWN_MASK, '%');

        // then
        assertThat(before).containsExactly(true);
        assertThat(shown).containsExactly(true, false);
        assertThat(next).containsExactly(PASS, PASS, PASS);
    }

    @Test
    @DisplayName("앞서 켠 prefix 의 타이머는 다시 켠 prefix 를 끄지 않는다")
    void staleTimerDoesNotCancelNewPrefix() {
        // given — 0초에 켜고 0.5초에 Esc 로 취소, 1초에 다시 켠다
        ShortcutKeys keys = keys(withPrefix("ctrl B"));
        stroke(keys, KeyEvent.VK_B, CTRL_DOWN_MASK, '\u0002');
        time.advance(Duration.ofMillis(500));
        stroke(keys, KeyEvent.VK_ESCAPE, 0, '\u001b');
        time.advance(Duration.ofMillis(500));
        stroke(keys, KeyEvent.VK_B, CTRL_DOWN_MASK, '\u0002');

        // when — 첫 타이머의 시각(1.5초)
        time.advance(Duration.ofMillis(600));

        // then
        assertThat(shown).containsExactly(true, false, true);

        // when — 두 번째 기한(2.5초)
        time.advance(Duration.ofMillis(900));

        // then
        assertThat(shown).containsExactly(true, false, true, false);
    }

    @Test
    @DisplayName("Ctrl+B 다음 Esc 는 취소다 — Esc 의 누름 · 글자는 셸로 가지 않는다")
    void escapeCancels() {
        // given
        ShortcutKeys keys = keys(withPrefix("ctrl B"));
        stroke(keys, KeyEvent.VK_B, CTRL_DOWN_MASK, '\u0002');

        // when
        List<KeyRoute> escape = stroke(keys, KeyEvent.VK_ESCAPE, 0, '\u001b');

        // then
        assertThat(escape).containsExactly(SWALLOW, SWALLOW, PASS);
        assertThat(shown).containsExactly(true, false);
    }

    @Test
    @DisplayName("prefix 모드 중에 설정에서 prefix 를 지우면 표시가 사라지고 Ctrl+B 는 셸로 간다 — 다시 시작하지 않는다")
    void removingPrefixTurnsItOff() {
        // given
        ShortcutKeys keys = keys(withPrefix("ctrl B"));
        stroke(keys, KeyEvent.VK_B, CTRL_DOWN_MASK, '\u0002');

        // when
        keys.keymap(Keymap.defaults());
        List<KeyRoute> routes = stroke(keys, KeyEvent.VK_B, CTRL_DOWN_MASK, '\u0002');

        // then
        assertThat(shown).containsExactly(true, false);
        assertThat(routes).containsExactly(PASS, PASS, PASS);
    }

    @Test
    @DisplayName("설정에서 prefix 를 바꾸면 새 키가 prefix 다")
    void changedPrefixApplies() {
        // given
        ShortcutKeys keys = keys(Keymap.defaults());

        // when
        keys.keymap(withPrefix("ctrl Q"));
        List<KeyRoute> routes = stroke(keys, KeyEvent.VK_Q, CTRL_DOWN_MASK, '\u0011');

        // then
        assertThat(routes).containsExactly(SWALLOW, SWALLOW, PASS);
        assertThat(shown).containsExactly(true);
    }

    @Test
    @DisplayName("창이 직접 쓴 키(끌기 취소 Esc)의 글자도 셸로 가지 않는다")
    void consumedPressSwallowsItsCharacter() {
        // given
        ShortcutKeys keys = keys(Keymap.defaults());

        // when
        keys.consumedPress();
        KeyRoute typed = keys.route(typed(0, '\u001b'));
        KeyRoute release = keys.route(released(KeyEvent.VK_ESCAPE, 0, '\u001b'));

        // then
        assertThat(typed).isEqualTo(SWALLOW);
        assertThat(release).isEqualTo(PASS);
    }
}
