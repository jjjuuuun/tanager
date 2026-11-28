package tanager.ui.keys;

import static java.awt.event.InputEvent.CTRL_DOWN_MASK;
import static java.awt.event.InputEvent.SHIFT_DOWN_MASK;
import static org.assertj.core.api.Assertions.assertThat;

import java.awt.event.KeyEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tanager.domain.layout.Direction;
import tanager.domain.layout.Orientation;

/** tmux 스타일 prefix 모드의 순수 상태 기계(FR-LAY-08, SDD §8.2 prefix 열, #193). 시간은 인자로 받는다. */
class PrefixStateTest {
    private static final Key CTRL_B = new Key(KeyEvent.VK_B, CTRL_DOWN_MASK);
    private static final Instant T0 = Instant.parse("2026-11-28T00:00:00Z");
    private static final KeyInput PREFIX = new KeyInput.Pressed(KeyEvent.VK_B, CTRL_DOWN_MASK);

    private static PrefixState armed() {
        PrefixStep step = PrefixState.idle(CTRL_B).on(PREFIX, T0);
        assertThat(step.route()).isEqualTo(new KeyRoute.Swallow());
        return step.next();
    }

    @Test
    @DisplayName("prefix 를 누르면 그 키를 삼키고 prefix 모드가 된다 — 1.5초 뒤가 기한이다")
    void prefixArms() {
        // when
        PrefixStep step = PrefixState.idle(CTRL_B).on(PREFIX, T0);

        // then
        assertThat(step.route()).isEqualTo(new KeyRoute.Swallow());
        assertThat(step.next().active()).isTrue();
        assertThat(step.next().deadline()).contains(T0.plus(PrefixState.TIMEOUT));
        assertThat(PrefixState.TIMEOUT).isEqualTo(Duration.ofMillis(1500));
    }

    @Test
    @DisplayName("prefix 모드가 아니면 다른 키는 건드리지 않는다")
    void idlePassesOtherKeys() {
        // given
        PrefixState idle = PrefixState.idle(CTRL_B);

        // when
        PrefixStep step = idle.on(new KeyInput.Pressed(KeyEvent.VK_A, 0), T0);

        // then
        assertThat(step.route()).isEqualTo(new KeyRoute.Pass());
        assertThat(step.next().active()).isFalse();
        assertThat(step.next().deadline()).isEmpty();
    }

    @Test
    @DisplayName("prefix 가 만든 글자(0x02) · 키를 뗌 · 수식키만 누름은 prefix 모드를 끝내지 않는다")
    void armedIgnoresItsOwnTypedReleaseAndModifiers() {
        // given
        PrefixState state = armed();

        // when
        PrefixStep typed = state.on(new KeyInput.Typed('\u0002'), T0);
        PrefixStep released = typed.next().on(new KeyInput.Released(KeyEvent.VK_B), T0);
        PrefixStep shift = released.next().on(new KeyInput.Pressed(KeyEvent.VK_SHIFT, SHIFT_DOWN_MASK), T0);

        // then
        assertThat(typed.route()).isEqualTo(new KeyRoute.Pass());
        assertThat(released.route()).isEqualTo(new KeyRoute.Pass());
        assertThat(shift.route()).isEqualTo(new KeyRoute.Pass());
        assertThat(shift.next().active()).isTrue();
    }

    @Test
    @DisplayName("prefix 다음 % 는 키 코드가 아니라 글자로 판정해 오른쪽 분할이다 — 누른 키는 글자가 올 때까지 삼킨다")
    void percentSplitsRight() {
        // given
        PrefixState state = armed();

        // when
        PrefixStep pressed = state.on(new KeyInput.Pressed(KeyEvent.VK_5, SHIFT_DOWN_MASK), T0);
        PrefixStep typed = pressed.next().on(new KeyInput.Typed('%'), T0);

        // then
        assertThat(pressed.route()).isEqualTo(new KeyRoute.Swallow());
        assertThat(pressed.next().active()).isTrue();
        assertThat(typed.route()).isEqualTo(new KeyRoute.Run(new LayoutAction.Split(Orientation.ROW)));
        assertThat(typed.next().active()).isFalse();
    }

    static Stream<Arguments> typedColumn() {
        return Stream.of(
                Arguments.of('%', new LayoutAction.Split(Orientation.ROW)),
                Arguments.of('"', new LayoutAction.Split(Orientation.COLUMN)),
                Arguments.of('z', new LayoutAction.Zoom()),
                Arguments.of('x', new LayoutAction.Close()),
                Arguments.of('{', new LayoutAction.Swap(Direction.LEFT)),
                Arguments.of('}', new LayoutAction.Swap(Direction.RIGHT)));
    }

    @ParameterizedTest(name = "{0} → {1}")
    @MethodSource("typedColumn")
    @DisplayName("SDD §8.2 prefix 열의 글자 키")
    void typedKeys(char typed, KeyAction expected) {
        // given — 자판마다 키 코드가 달라 아무 키 코드로 눌러도 글자가 정한다
        PrefixState awaiting =
                armed().on(new KeyInput.Pressed(KeyEvent.VK_Q, 0), T0).next();

        // when
        PrefixStep step = awaiting.on(new KeyInput.Typed(typed), T0);

        // then
        assertThat(step.route()).isEqualTo(new KeyRoute.Run(expected));
    }

    static Stream<Arguments> pressedColumn() {
        return Stream.of(
                Arguments.of(KeyEvent.VK_LEFT, 0, new LayoutAction.Focus(Direction.LEFT)),
                Arguments.of(KeyEvent.VK_RIGHT, 0, new LayoutAction.Focus(Direction.RIGHT)),
                Arguments.of(KeyEvent.VK_UP, 0, new LayoutAction.Focus(Direction.UP)),
                Arguments.of(KeyEvent.VK_DOWN, 0, new LayoutAction.Focus(Direction.DOWN)),
                Arguments.of(KeyEvent.VK_LEFT, CTRL_DOWN_MASK, new LayoutAction.Resize(Direction.LEFT)),
                Arguments.of(KeyEvent.VK_RIGHT, CTRL_DOWN_MASK, new LayoutAction.Resize(Direction.RIGHT)),
                Arguments.of(KeyEvent.VK_UP, CTRL_DOWN_MASK, new LayoutAction.Resize(Direction.UP)),
                Arguments.of(KeyEvent.VK_DOWN, CTRL_DOWN_MASK, new LayoutAction.Resize(Direction.DOWN)));
    }

    @ParameterizedTest(name = "{0}/{1} → {2}")
    @MethodSource("pressedColumn")
    @DisplayName("SDD §8.2 prefix 열의 화살표 · Ctrl+화살표는 누를 때 바로 판정한다")
    void pressedKeys(int keyCode, int modifiers, KeyAction expected) {
        // when
        PrefixStep step = armed().on(new KeyInput.Pressed(keyCode, modifiers), T0);

        // then
        assertThat(step.route()).isEqualTo(new KeyRoute.Run(expected));
        assertThat(step.next().active()).isFalse();
    }

    @Test
    @DisplayName("prefix 다음 Esc 는 취소다 — Esc 도 삼킨다")
    void escapeCancels() {
        // when
        PrefixStep step = armed().on(new KeyInput.Pressed(KeyEvent.VK_ESCAPE, 0), T0);

        // then
        assertThat(step.route()).isEqualTo(new KeyRoute.Swallow());
        assertThat(step.next().active()).isFalse();
    }

    @Test
    @DisplayName("prefix 를 두 번 누르면 두 번째는 그대로 셸로 간다(tmux 관례) — prefix 모드는 끝난다")
    void prefixTwicePassesTheSecond() {
        // when
        PrefixStep step = armed().on(PREFIX, T0.plusMillis(300));

        // then
        assertThat(step.route()).isEqualTo(new KeyRoute.Pass());
        assertThat(step.next().active()).isFalse();
    }

    @Test
    @DisplayName("prefix 열에 없는 글자는 삼키고 prefix 모드를 끝낸다(tmux 와 같다)")
    void unboundTypedIsSwallowed() {
        // given
        PrefixState awaiting =
                armed().on(new KeyInput.Pressed(KeyEvent.VK_Q, 0), T0).next();

        // when
        PrefixStep step = awaiting.on(new KeyInput.Typed('q'), T0);

        // then
        assertThat(step.route()).isEqualTo(new KeyRoute.Swallow());
        assertThat(step.next().active()).isFalse();
    }

    @Test
    @DisplayName("글자를 만들지 않는 키(F5)는 뗄 때 prefix 모드를 끝낸다")
    void keyWithoutCharacterEndsOnRelease() {
        // given
        PrefixStep pressed = armed().on(new KeyInput.Pressed(KeyEvent.VK_F5, 0), T0);

        // when
        PrefixStep released = pressed.next().on(new KeyInput.Released(KeyEvent.VK_F5), T0);

        // then
        assertThat(pressed.route()).isEqualTo(new KeyRoute.Swallow());
        assertThat(released.route()).isEqualTo(new KeyRoute.Pass());
        assertThat(released.next().active()).isFalse();
    }

    @Test
    @DisplayName("prefix 뒤 2초에 누른 키는 취소된 뒤라 그대로 셸로 간다")
    void timedOutKeyPasses() {
        // when
        PrefixStep step = armed().on(new KeyInput.Pressed(KeyEvent.VK_5, SHIFT_DOWN_MASK), T0.plusSeconds(2));

        // then
        assertThat(step.route()).isEqualTo(new KeyRoute.Pass());
        assertThat(step.next().active()).isFalse();
    }

    @Test
    @DisplayName("기한(1.5초)이 되는 순간 끝난다 — 바로 전까지는 prefix 열로 판정한다")
    void deadlineBoundary() {
        // given
        PrefixState state = armed();
        KeyInput left = new KeyInput.Pressed(KeyEvent.VK_LEFT, 0);

        // when
        PrefixStep before = state.on(left, T0.plus(PrefixState.TIMEOUT).minusMillis(1));
        PrefixStep at = state.on(left, T0.plus(PrefixState.TIMEOUT));

        // then
        assertThat(before.route()).isEqualTo(new KeyRoute.Run(new LayoutAction.Focus(Direction.LEFT)));
        assertThat(at.route()).isEqualTo(new KeyRoute.Pass());
    }

    @Test
    @DisplayName("기한이 지난 뒤 다시 prefix 를 누르면 새 기한으로 다시 켜진다")
    void rearmsAfterTimeout() {
        // given
        Instant later = T0.plusSeconds(5);

        // when
        PrefixStep step = armed().on(PREFIX, later);

        // then
        assertThat(step.route()).isEqualTo(new KeyRoute.Swallow());
        assertThat(step.next().deadline()).contains(later.plus(PrefixState.TIMEOUT));
    }

    @Test
    @DisplayName("expire 는 기한 전에는 그대로이고 기한이 지나면 끈다 — 키 없이 표시를 지우는 타이머용")
    void expire() {
        // given
        PrefixState state = armed();

        // then
        assertThat(state.expire(T0.plusMillis(1000)).active()).isTrue();
        assertThat(state.expire(T0.plus(PrefixState.TIMEOUT)).active()).isFalse();
        assertThat(state.expire(T0.plus(PrefixState.TIMEOUT)).prefix()).isEqualTo(CTRL_B);
    }
}
