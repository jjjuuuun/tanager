package tanager.ui.motion;

import java.util.Objects;
import java.util.function.DoubleConsumer;
import java.util.function.LongSupplier;

/**
 * 값 하나를 지금 값에서 목표까지 {@link Motion#duration()} 동안 ease-out 으로 옮긴다(FR-UI-04). 프레임마다 {@code setter} 를 부르고,
 * OS 가 애니메이션을 껐으면 바로 최종 값을 넣는다. 도중에 다른 목표를 주면 지금 값에서 다시 출발하고 이전 끝 콜백은 버린다.
 *
 * <p>EDT 에서만 쓴다. 지금 쓰는 곳은 포커스 테두리(PaneController)와 사이드바(SidebarSlot)다. 드롭 영역 미리보기(#151)와 줌(#149)도
 * 이 클래스로 전환한다.
 */
public final class Animator {
    private static final Runnable NOTHING = () -> {};

    private final Motion motion;
    private final Ticker ticker;
    private final LongSupplier nanoTime;
    private final DoubleConsumer setter;
    private double value;
    private double from;
    private double target;
    private long startedAt;
    private boolean running;
    private Runnable onFinish = NOTHING;

    /** Swing 타이머와 시스템 시계로 돈다. {@code initial} 은 지금 화면에 보이는 값이다(setter 를 부르지 않는다). */
    public Animator(Motion motion, double initial, DoubleConsumer setter) {
        this(motion, new SwingTicker(), System::nanoTime, initial, setter);
    }

    Animator(Motion motion, Ticker ticker, LongSupplier nanoTime, double initial, DoubleConsumer setter) {
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
        if (motion.instant()) {
            jumpTo(next);
            finished.run();
            return;
        }
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
        double progress =
                (nanoTime.getAsLong() - startedAt) / (double) motion.duration().toNanos();
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
