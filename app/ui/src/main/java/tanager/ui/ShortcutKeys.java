package tanager.ui;

import java.awt.event.KeyEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import tanager.ui.keys.KeyAction;
import tanager.ui.keys.KeyInput;
import tanager.ui.keys.KeyRoute;
import tanager.ui.keys.Keymap;
import tanager.ui.keys.PrefixState;
import tanager.ui.keys.PrefixStep;

/**
 * 메인 창이 터미널보다 먼저 보는 키의 판정(#148 · #183)과 prefix 모드(FR-LAY-08, #193). 키 이벤트 하나마다 셸로 보낼지 · 삼킬지 · 동작을 부를지
 * 정한다. prefix 모드가 먼저 보고, 다루지 않은 키만 키 표로 판정한다.
 *
 * <p>가로챈 키가 만드는 글자(KEY_TYPED)도 터미널에 보내지 않는다 — Alt+Shift+= 의 '+' 가 셸로 새지 않게. 다음 키를 떼면 풀린다. prefix 모드가
 * 켜지면 기한에 타이머를 걸어 키를 누르지 않아도 표시를 끈다. EDT 에서만 쓴다 — 타이머도 EDT 에서 불러야 한다.
 */
final class ShortcutKeys {
    private final InstantSource clock;
    private final Coalescer.Timer timer;
    private final Consumer<Boolean> prefixShown;
    private Keymap keymap;
    private Optional<PrefixState> prefix;
    private boolean swallowTyped;
    private boolean shown;
    // 걸어 둔 기한 타이머의 세대. 새로 켜거나 키 표를 바꾸면 올려서 앞서 걸린 타이머를 무시한다
    private long generation;

    /** @param prefixShown prefix 모드 표시를 켜고(true) 끈다. 바뀔 때만 부른다 */
    ShortcutKeys(Keymap keymap, InstantSource clock, Coalescer.Timer timer, Consumer<Boolean> prefixShown) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.prefixShown = Objects.requireNonNull(prefixShown, "prefixShown");
        keymap(keymap);
    }

    /** 설정이 바뀌어 키 표를 바꾼다. 진행 중인 prefix 모드는 끝난다 — prefix 를 지웠으면 prefix 모드가 꺼진다. */
    void keymap(Keymap next) {
        keymap = Objects.requireNonNull(next, "next");
        prefix = next.prefix().map(PrefixState::idle);
        generation++;
        show(false);
    }

    /** 창이 이 누름을 직접 썼다(끌기 취소 Esc) — 그 키의 글자도 셸로 보내지 않는다. */
    void consumedPress() {
        swallowTyped = true;
    }

    /** 키 이벤트 하나의 판정. */
    KeyRoute route(KeyEvent event) {
        Optional<KeyInput> input = KeyInput.of(event);
        if (input.isEmpty()) {
            return new KeyRoute.Pass();
        }
        KeyRoute byPrefix = prefixRoute(input.get());
        if (!(byPrefix instanceof KeyRoute.Pass)) {
            if (input.get() instanceof KeyInput.Pressed) {
                swallowTyped = true;
            }
            return byPrefix;
        }
        return keymapRoute(input.get());
    }

    private KeyRoute prefixRoute(KeyInput input) {
        if (prefix.isEmpty()) {
            return new KeyRoute.Pass();
        }
        PrefixState before = prefix.get();
        PrefixStep step = before.on(input, clock.instant());
        prefix = Optional.of(step.next());
        Optional<Instant> deadline = step.next().deadline();
        if (deadline.isPresent() && !deadline.equals(before.deadline())) {
            long armed = ++generation;
            schedule(deadline.get(), armed);
        }
        show(step.next().active());
        return step.route();
    }

    private KeyRoute keymapRoute(KeyInput input) {
        return switch (input) {
            case KeyInput.Pressed(int keyCode, int modifiers) -> {
                Optional<KeyAction> action = keymap.actionFor(keyCode, modifiers);
                swallowTyped = action.isPresent();
                yield action.<KeyRoute>map(KeyRoute.Run::new).orElse(new KeyRoute.Pass());
            }
            case KeyInput.Typed typed -> swallowTyped ? new KeyRoute.Swallow() : new KeyRoute.Pass();
            case KeyInput.Released released -> {
                swallowTyped = false;
                yield new KeyRoute.Pass();
            }
        };
    }

    // Swing 타이머는 시계보다 조금 일찍 깰 수 있다 — 기한 전이면 남은 만큼 다시 건다
    private void schedule(Instant deadline, long armed) {
        Duration remaining = Duration.between(clock.instant(), deadline);
        timer.after(remaining.isNegative() ? Duration.ZERO : remaining, () -> due(armed));
    }

    private void due(long armed) {
        if (armed != generation || prefix.isEmpty()) {
            return;
        }
        PrefixState expired = prefix.get().expire(clock.instant());
        prefix = Optional.of(expired);
        if (expired.active()) {
            expired.deadline().ifPresent(deadline -> schedule(deadline, armed));
        }
        show(expired.active());
    }

    private void show(boolean on) {
        if (shown != on) {
            shown = on;
            prefixShown.accept(on);
        }
    }
}
