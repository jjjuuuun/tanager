package tanager.ui.measure;

import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * EDT 정지 감시(NFR-01 · NFR-10, #222). {@link #PERIOD} 주기 Swing 타이머가 {@link #ticked()} 를 부르고, 늦게 온 만큼(지연)이
 * {@link #LIMIT} 을 넘으면 한 줄을 남긴다: {@code edt-stall delay=150ms stack=…}.
 *
 * <p>타이머는 정지가 끝난 뒤에야 오므로 그때의 스택은 쓸모가 없다 — 다른 스레드가 같은 주기로 {@link #watch()} 를 불러, 멈춘 동안 EDT 스택
 * 상위 {@value #STACK_LINES}줄을 한 번 떠 둔다. 감시 스레드가 보지 못한 정지는 {@code stack=-}.
 */
final class EdtStallWatch {
    static final Duration PERIOD = Duration.ofMillis(50);
    static final Duration LIMIT = Duration.ofMillis(100);
    static final int STACK_LINES = 5;

    private final LongSupplier nanos;
    private final Supplier<StackTraceElement[]> edtStack;
    private final Consumer<String> log;
    private final long period = PERIOD.toNanos();
    private final long limit = LIMIT.toNanos();

    // 아래는 this 로 지킨다(EDT 와 감시 스레드)
    private boolean started;
    private long lastTick;
    private Optional<String> stallStack = Optional.empty();

    /**
     * @param edtStack 지금 EDT 의 스택. 감시 스레드에서 부른다
     * @param log 정지 한 줄을 받는다. EDT 에서 부른다
     */
    EdtStallWatch(LongSupplier nanos, Supplier<StackTraceElement[]> edtStack, Consumer<String> log) {
        this.nanos = Objects.requireNonNull(nanos, "nanos");
        this.edtStack = Objects.requireNonNull(edtStack, "edtStack");
        this.log = Objects.requireNonNull(log, "log");
    }

    /** 타이머 한 박자. EDT. 첫 박자는 기준만 잡는다. */
    void ticked() {
        Optional<String> line;
        synchronized (this) {
            long now = nanos.getAsLong();
            long delay = now - lastTick - period;
            line = started && delay > limit ? Optional.of(line(delay, stallStack)) : Optional.empty();
            started = true;
            lastTick = now;
            stallStack = Optional.empty();
        }
        line.ifPresent(log);
    }

    /** 감시 스레드. 타이머가 {@link #LIMIT} 넘게 늦어지고 있으면 그 정지의 EDT 스택을 한 번 떠 둔다. */
    void watch() {
        long stalledSince;
        synchronized (this) {
            if (!started || stallStack.isPresent() || nanos.getAsLong() - lastTick - period <= limit) {
                return;
            }
            stalledSince = lastTick;
        }
        // 스택 뜨기는 EDT 를 잠깐 세운다(안전점) — 잠금 밖에서 해 EDT 의 ticked 를 막지 않는다
        String stack = top(edtStack.get());
        synchronized (this) {
            // 그 사이 타이머가 왔으면 정지는 끝났다 — 다음 박자에 남의 스택을 붙이지 않는다
            if (stallStack.isEmpty() && lastTick == stalledSince) {
                stallStack = Optional.of(stack);
            }
        }
    }

    private static String line(long delayNanos, Optional<String> stack) {
        return "edt-stall delay=" + TimeUnit.NANOSECONDS.toMillis(delayNanos) + "ms stack=" + stack.orElse("-");
    }

    private static String top(StackTraceElement[] frames) {
        if (frames.length == 0) {
            return "-";
        }
        return Arrays.stream(frames)
                .limit(STACK_LINES)
                .map(StackTraceElement::toString)
                .collect(Collectors.joining(" < "));
    }
}
