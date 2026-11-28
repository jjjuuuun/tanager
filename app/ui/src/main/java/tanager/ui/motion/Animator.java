package tanager.ui.motion;

import java.time.Duration;
import java.util.Objects;
import java.util.function.DoubleConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 값 하나를 지금 값에서 목표까지 {@link Motion#duration()} 동안 ease-out 으로 옮긴다(FR-UI-04). 프레임마다 {@code setter} 를 부르고,
 * OS 가 애니메이션을 껐으면 바로 최종 값을 넣는다. 도중에 다른 목표를 주면 지금 값에서 다시 출발하고 이전 끝 콜백은 버린다.
 *
 * <p>{@link Motion} 은 전환을 시작할 때마다 공급자에서 새로 읽는다 — 실행 중에 OS · 설정의 애니메이션이 바뀌면 다음 전환부터 따른다(#249).
 * 진행 중인 전환은 시작할 때의 값으로 끝까지 간다(도중에 끝 값으로 뛰면 깜빡인다).
 *
 * <p>EDT 에서만 쓴다. 지금 쓰는 곳은 포커스 테두리(PaneController)와 사이드바(SidebarSlot)다. 드롭 영역 미리보기(#151)와 줌(#149)도
 * 이 클래스로 전환한다.
 */
public final class Animator {
    private static final Runnable NOTHING = () -> {};

    private final Supplier<Motion> motion;
    private final Ticker ticker;
    private final LongSupplier nanoTime;
    private final DoubleConsumer setter;
    private double value;
    private double from;
    private double target;
    private long startedAt;
    private boolean running;
    private Runnable onFinish = NOTHING;
    // 진행 중인 전환의 길이 — 시작할 때 정한다
    private Duration duration = Duration.ZERO;

    /** 늘 같은 {@code motion} 으로 돈다. {@link #Animator(Supplier, double, DoubleConsumer)} 와 같다. */
    public Animator(Motion motion, double initial, DoubleConsumer setter) {
        this(() -> motion, initial, setter);
    }

    /**
     * Swing 타이머와 시스템 시계로 돈다. {@code motion} 은 전환을 시작할 때마다 부른다(EDT). {@code initial} 은 지금 화면에 보이는 값이다(setter
     * 를 부르지 않는다).
     */
    public Animator(Supplier<Motion> motion, double initial, DoubleConsumer setter) {
        this(motion, new SwingTicker(), System::nanoTime, initial, setter);
    }

    Animator(Supplier<Motion> motion, Ticker ticker, LongSupplier nanoTime, double initial, DoubleConsumer setter) {
        this.motion = Objects.requireNonNull(motion, "motion");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.setter = Objects.requireNonNull(setter, "setter");
        this.value = initial;
        this.target = initial;
    }

    /** {@link #animateTo(double, Runnable)} 와 같고 끝 콜백이 없다. */
    public void animateTo(double next) {
        animateTo(next, NOTHING);
    }

    /**
     * {@code next} 로 옮긴다. 다 가면 {@code finished} 를 한 번 부른다 — 다른 목표로 바뀌거나 {@link #cancel} 되면 부르지 않는다. 이미 그
     * 목표로 가는 중이면 처음부터 다시 시작하지 않는다.
     */
    public void animateTo(double next, Runnable finished) {
        Objects.requireNonNull(finished, "finished");
        if (running && next == target) {
            onFinish = finished;
            return;
        }
        if (!running && next == value) {
            onFinish = NOTHING;
            finished.run();
            return;
        }
        Motion now = motion.get();
        if (now.instant()) {
            jumpTo(next);
            finished.run();
            return;
        }
        duration = now.duration();
        from = value;
        target = next;
        startedAt = nanoTime.getAsLong();
        onFinish = finished;
        running = true;
        ticker.start(this::tick);
    }

    /** 진행 중인 전환을 버리고 {@code next} 를 바로 넣는다. */
    public void jumpTo(double next) {
        stopTicking();
        value = next;
        target = next;
        setter.accept(next);
    }

    /** 지금 값에서 멈춘다. 끝 콜백은 부르지 않는다. */
    public void cancel() {
        stopTicking();
        target = value;
    }

    /** 마지막으로 넣은 값. */
    public double value() {
        return value;
    }

    /** 전환이 진행 중인가. */
    public boolean running() {
        return running;
    }

    private void tick() {
        if (!running) {
            return;
        }
        double progress = (nanoTime.getAsLong() - startedAt) / (double) duration.toNanos();
        value = Lerp.number(from, target, Easing.easeOut(progress));
        setter.accept(value);
        if (progress >= 1) {
            Runnable finished = onFinish;
            stopTicking();
            finished.run();
        }
    }

    private void stopTicking() {
        running = false;
        onFinish = NOTHING;
        ticker.stop();
    }
}
