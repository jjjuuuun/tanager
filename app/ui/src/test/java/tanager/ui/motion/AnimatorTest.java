package tanager.ui.motion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AnimatorTest {
    private static final Motion ON = new Motion(Duration.ofMillis(200), true);
    private static final Motion OFF = new Motion(Duration.ofMillis(200), false);

    private final ManualTicker ticker = new ManualTicker();
    private final List<Double> applied = new ArrayList<>();

    private Animator animator(Motion motion, double initial) {
        return animator(() -> motion, initial);
    }

    private Animator animator(Supplier<Motion> motion, double initial) {
        return new Animator(motion, ticker, ticker::nanoTime, initial, applied::add);
    }

    @Test
    @DisplayName("애니메이션을 끄면 바로 최종 값을 넣고 끝 콜백을 부른다")
    void disabledAppliesFinalValueImmediately() {
        // given
        Animator animator = animator(OFF, 0);
        AtomicInteger finished = new AtomicInteger();

        // when
        animator.animateTo(1, finished::incrementAndGet);

        // then
        assertThat(applied).containsExactly(1.0);
        assertThat(animator.value()).isEqualTo(1.0);
        assertThat(finished).hasValue(1);
        assertThat(ticker.starts()).isZero();
    }

    @Test
    @DisplayName("켜져 있으면 프레임마다 ease-out 값을 넣고 시간이 다 되면 정확히 끝 값에서 멈춘다")
    void animatesOverDuration() {
        // given
        Animator animator = animator(ON, 0);
        AtomicInteger finished = new AtomicInteger();

        // when
        animator.animateTo(10, finished::incrementAndGet);
        ticker.advance(100);

        // then — 절반 시간에 ease-out 값만큼 와 있다
        assertThat(applied).last().isEqualTo(10 * Easing.easeOut(0.5));
        assertThat(finished).hasValue(0);
        assertThat(animator.running()).isTrue();

        // when
        ticker.advance(150);

        // then
        assertThat(applied).last().isEqualTo(10.0);
        assertThat(animator.value()).isEqualTo(10.0);
        assertThat(finished).hasValue(1);
        assertThat(animator.running()).isFalse();
        assertThat(ticker.running()).isFalse();
    }

    @Test
    @DisplayName("값은 시작부터 끝까지 한 방향으로만 움직인다")
    void monotonicFrames() {
        // given
        Animator animator = animator(ON, 0);

        // when
        animator.animateTo(1);
        for (int i = 0; i < 20; i++) {
            ticker.advance(16);
        }

        // then
        assertThat(applied).isSorted().last().isEqualTo(1.0);
    }

    @Test
    @DisplayName("도중에 다른 목표를 주면 지금 값에서 새 목표로 가고 이전 끝 콜백은 부르지 않는다")
    void retargetFromCurrentValue() {
        // given
        Animator animator = animator(ON, 0);
        AtomicInteger shown = new AtomicInteger();
        AtomicInteger hidden = new AtomicInteger();
        animator.animateTo(1, shown::incrementAndGet);
        ticker.advance(100);
        double midway = animator.value();

        // when
        animator.animateTo(0, hidden::incrementAndGet);
        ticker.advance(1);

        // then — 되돌아가기 시작한 값은 지금 값보다 작고 0 보다 크다
        assertThat(animator.value()).isLessThan(midway).isGreaterThan(0.0);

        // when
        ticker.advance(300);

        // then
        assertThat(animator.value()).isEqualTo(0.0);
        assertThat(hidden).hasValue(1);
        assertThat(shown).hasValue(0);
        assertThat(ticker.running()).isFalse();
    }

    @Test
    @DisplayName("같은 목표로 다시 부르면 처음부터 다시 시작하지 않는다")
    void sameTargetKeepsProgress() {
        // given
        Animator animator = animator(ON, 0);
        animator.animateTo(1);
        ticker.advance(100);
        double before = animator.value();

        // when
        animator.animateTo(1);
        ticker.advance(100);

        // then — 남은 100ms 가 지나 끝났다
        assertThat(before).isLessThan(1.0);
        assertThat(animator.value()).isEqualTo(1.0);
        assertThat(animator.running()).isFalse();
    }

    @Test
    @DisplayName("이미 목표 값이면 값을 다시 넣지 않고 끝 콜백만 부른다")
    void alreadyAtTarget() {
        // given
        Animator animator = animator(ON, 1);
        AtomicInteger finished = new AtomicInteger();

        // when
        animator.animateTo(1, finished::incrementAndGet);

        // then
        assertThat(applied).isEmpty();
        assertThat(finished).hasValue(1);
        assertThat(ticker.starts()).isZero();
    }

    @Test
    @DisplayName("cancel 은 지금 값에서 멈추고 끝 콜백을 부르지 않는다")
    void cancelStopsWhereItIs() {
        // given
        Animator animator = animator(ON, 0);
        AtomicInteger finished = new AtomicInteger();
        animator.animateTo(1, finished::incrementAndGet);
        ticker.advance(50);
        double stopped = animator.value();

        // when
        animator.cancel();
        ticker.advance(500);

        // then
        assertThat(animator.value()).isEqualTo(stopped);
        assertThat(animator.running()).isFalse();
        assertThat(ticker.running()).isFalse();
        assertThat(finished).hasValue(0);
    }

    @Test
    @DisplayName("jumpTo 는 진행 중인 전환을 멈추고 값을 바로 넣는다")
    void jumpToCancelsAndApplies() {
        // given
        Animator animator = animator(ON, 0);
        animator.animateTo(1);
        ticker.advance(50);

        // when
        animator.jumpTo(0);

        // then
        assertThat(applied).last().isEqualTo(0.0);
        assertThat(animator.value()).isEqualTo(0.0);
        assertThat(animator.running()).isFalse();
        assertThat(ticker.running()).isFalse();
    }

    @Test
    @DisplayName("켜짐으로 시작해 실행 중 꺼지면 다음 전환은 전환 없이 바로 끝난다(#249)")
    void turnedOffWhileRunningAppliesNextInstantly() {
        // given
        AtomicReference<Motion> live = new AtomicReference<>(ON);
        Animator animator = animator(live::get, 0);
        animator.animateTo(1);
        ticker.advance(250);
        AtomicInteger finished = new AtomicInteger();

        // when
        live.set(OFF);
        animator.animateTo(0, finished::incrementAndGet);

        // then
        assertThat(applied).last().isEqualTo(0.0);
        assertThat(finished).hasValue(1);
        assertThat(animator.running()).isFalse();
        assertThat(ticker.starts()).isEqualTo(1);
    }

    @Test
    @DisplayName("꺼짐으로 시작해 실행 중 켜지면 다음 전환부터 그 전환 시간으로 움직인다(#249)")
    void turnedOnWhileRunningAnimatesNext() {
        // given
        AtomicReference<Motion> live = new AtomicReference<>(OFF);
        Animator animator = animator(live::get, 0);
        animator.animateTo(1);
        AtomicInteger finished = new AtomicInteger();

        // when
        live.set(new Motion(Duration.ofMillis(180), true));
        animator.animateTo(0, finished::incrementAndGet);
        ticker.advance(90);

        // then — 180ms 의 절반에 ease-out 값만큼 와 있다
        assertThat(animator.running()).isTrue();
        assertThat(animator.value()).isEqualTo(1 - Easing.easeOut(0.5));
        assertThat(finished).hasValue(0);

        // when
        ticker.advance(90);

        // then
        assertThat(animator.value()).isEqualTo(0.0);
        assertThat(finished).hasValue(1);
        assertThat(animator.running()).isFalse();
    }

    @Test
    @DisplayName("전환 중에 설정이 바뀌어도 진행 중인 전환은 시작할 때의 값으로 끝까지 간다 — 깜빡임 없음(#249)")
    void changeDuringTransitionKeepsRunningOne() {
        // given
        AtomicReference<Motion> live = new AtomicReference<>(ON);
        Animator animator = animator(live::get, 0);
        AtomicInteger finished = new AtomicInteger();
        animator.animateTo(1, finished::incrementAndGet);
        ticker.advance(100);

        // when — 꺼지고 시간도 달라진다
        live.set(new Motion(Duration.ofMillis(400), false));
        ticker.advance(50);

        // then — 끝 값으로 뛰지 않고 처음 200ms 기준으로 간다
        assertThat(animator.running()).isTrue();
        assertThat(animator.value()).isEqualTo(Easing.easeOut(150 / 200.0));

        // when — 같은 목표로 다시 불러도 이어서 간다
        animator.animateTo(1, finished::incrementAndGet);
        ticker.advance(50);

        // then
        assertThat(animator.value()).isEqualTo(1.0);
        assertThat(finished).hasValue(1);
        assertThat(animator.running()).isFalse();
    }
}
