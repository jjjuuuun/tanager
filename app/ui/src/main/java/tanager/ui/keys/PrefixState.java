package tanager.ui.keys;

import java.awt.event.KeyEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * tmux 스타일 prefix 모드(FR-LAY-08, #193)의 순수 상태 기계. 불변이며 시간은 인자로 받는다 — 키 하나마다 {@link #on} 이 다음 상태와 판정을 돌려준다.
 *
 * <ul>
 *   <li>prefix 를 누르면 {@link #TIMEOUT} 동안 다음 키 하나를 SDD §8.2 prefix 열({@link PrefixTable})로 해석한다. 그 사이 키는 셸로
 *       가지 않는다.
 *   <li>화살표 · Ctrl+화살표는 누를 때, {@code %} · {@code "} 같은 글자 키는 자판마다 키 코드가 달라 그 키가 만든 글자로 판정한다. 열에 없는
 *       키는 삼키고 끝낸다(tmux 와 같다). 글자를 만들지 않는 키는 뗄 때 끝난다.
 *   <li>Esc · 시간 초과는 취소다. prefix 를 두 번 누르면 두 번째는 그대로 셸로 간다({@link KeyRoute.Pass}) — 터미널이 그 키를 평소대로
 *       보낸다(Ctrl+B → 0x02).
 *   <li>prefix 가 만든 글자 · 키를 뗌 · 수식키만 누름은 다음 키로 치지 않는다.
 * </ul>
 */
public record PrefixState(Key prefix, Phase phase) {
    /** prefix 를 누른 뒤 다음 키를 기다리는 시간. */
    public static final Duration TIMEOUT = Duration.ofMillis(1500);

    // 혼자서는 아무것도 아닌 키. 수식키를 누르는 동안은 prefix 모드가 이어진다
    private static final Set<Integer> MODIFIER_KEYS = Set.of(
            KeyEvent.VK_SHIFT,
            KeyEvent.VK_CONTROL,
            KeyEvent.VK_ALT,
            KeyEvent.VK_ALT_GRAPH,
            KeyEvent.VK_META,
            KeyEvent.VK_WINDOWS,
            KeyEvent.VK_CAPS_LOCK);

    public PrefixState {
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(phase, "phase");
    }

    /** prefix 모드의 단계. */
    public sealed interface Phase {
        /** prefix 를 기다린다. */
        record Idle() implements Phase {}

        /** prefix 를 눌렀다 — 다음 키를 기다린다. */
        record Armed(Instant since) implements Phase {
            public Armed {
                Objects.requireNonNull(since, "since");
            }
        }

        /** 다음 키를 눌렀고 prefix 열에 그 키 코드가 없다 — 그 키가 만들 글자를 기다린다. */
        record Awaiting(Instant since) implements Phase {
            public Awaiting {
                Objects.requireNonNull(since, "since");
            }
        }
    }

    /** {@code prefix} 를 기다리는 상태. */
    public static PrefixState idle(Key prefix) {
        return new PrefixState(prefix, new Phase.Idle());
    }

    /** prefix 모드 중이다(상태줄 표시). */
    public boolean active() {
        return !(phase instanceof Phase.Idle);
    }

    /** prefix 모드가 끝나는 시각. prefix 모드가 아니면 비어 있다. */
    public Optional<Instant> deadline() {
        return switch (phase) {
            case Phase.Idle idle -> Optional.empty();
            case Phase.Armed(Instant since) -> Optional.of(since.plus(TIMEOUT));
            case Phase.Awaiting(Instant since) -> Optional.of(since.plus(TIMEOUT));
        };
    }

    /** 기한이 지났으면 prefix 를 기다리는 상태, 아니면 그대로. 키 없이 표시를 지우는 타이머가 부른다. */
    public PrefixState expire(Instant now) {
        Objects.requireNonNull(now, "now");
        boolean expired = deadline().filter(due -> !now.isBefore(due)).isPresent();
        return expired ? idle(prefix) : this;
    }

    /** 키 입력 하나를 {@code now} 에 받았다. */
    public PrefixStep on(KeyInput input, Instant now) {
        Objects.requireNonNull(input, "input");
        PrefixState current = expire(now);
        return switch (current.phase) {
            case Phase.Idle idle -> current.idleOn(input, now);
            case Phase.Armed(Instant since) -> current.armedOn(input, since);
            case Phase.Awaiting(Instant since) -> current.awaitingOn(input, since);
        };
    }

    private PrefixStep idleOn(KeyInput input, Instant now) {
        if (isPrefix(input)) {
            return new PrefixStep(new PrefixState(prefix, new Phase.Armed(now)), new KeyRoute.Swallow());
        }
        return stay(new KeyRoute.Pass());
    }

    private PrefixStep armedOn(KeyInput input, Instant since) {
        return switch (input) {
            case KeyInput.Pressed pressed -> next(pressed, since);
            // prefix 가 만든 글자(0x02) · 뗌은 다음 키가 아니다
            case KeyInput.Typed typed -> stay(new KeyRoute.Pass());
            case KeyInput.Released released -> stay(new KeyRoute.Pass());
        };
    }

    private PrefixStep awaitingOn(KeyInput input, Instant since) {
        return switch (input) {
            case KeyInput.Pressed pressed -> next(pressed, since);
            case KeyInput.Typed(char character) ->
                done(PrefixTable.typed(character)
                        .<KeyRoute>map(KeyRoute.Run::new)
                        .orElse(new KeyRoute.Swallow()));
            // 글자를 만들지 않는 키(F5 등)를 뗐다 — 그 키는 이미 삼켰다
            case KeyInput.Released released -> done(new KeyRoute.Pass());
        };
    }

    // prefix 다음에 누른 키
    private PrefixStep next(KeyInput.Pressed pressed, Instant since) {
        if (MODIFIER_KEYS.contains(pressed.keyCode())) {
            return stay(new KeyRoute.Pass());
        }
        if (isPrefix(pressed)) {
            return done(new KeyRoute.Pass());
        }
        if (pressed.keyCode() == KeyEvent.VK_ESCAPE && pressed.modifiers() == 0) {
            return done(new KeyRoute.Swallow());
        }
        Optional<KeyAction> action = PrefixTable.pressed(pressed.keyCode(), pressed.modifiers());
        if (action.isPresent()) {
            return done(new KeyRoute.Run(action.get()));
        }
        return new PrefixStep(new PrefixState(prefix, new Phase.Awaiting(since)), new KeyRoute.Swallow());
    }

    private boolean isPrefix(KeyInput input) {
        return input instanceof KeyInput.Pressed(int keyCode, int modifiers)
                && keyCode == prefix.keyCode()
                && modifiers == prefix.modifiers();
    }

    private PrefixStep stay(KeyRoute route) {
        return new PrefixStep(this, route);
    }

    private PrefixStep done(KeyRoute route) {
        return new PrefixStep(idle(prefix), route);
    }
}
