package tanager.ui.measure;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import tanager.domain.shared.PaneId;

/**
 * 창 하나의 키 → 화면 반영 시간(NFR-01, #222). 키를 보낸 시각 t0 과, 그 뒤 첫 출력 조각이 버퍼에 들어간 다음 화면을 다 그린 시각 t1 의
 * 차이가 표본이다. 짝은 창마다 FIFO 이고, 출력 조각 하나는 그보다 먼저 보낸 키를 모두 답한다 — 키 하나에 조각 하나로 짝지으면 출력이 없는
 * 키(빈 줄의 Esc 등) 하나가 뒤의 모든 표본을 한 키 간격씩 밀었다(실측: 100ms 간격 타이핑의 p50 이 110 → 310ms 로 계속 늘었다). 출력 없이
 * 그린 것(커서 깜빡임)은 짝이 아니다. 키 뒤 {@link #EXPIRY} 안에 그려지지 않으면 버린다.
 *
 * <p>키 · 그림(EDT)과 출력(에뮬레이터 스레드)이 함께 만지므로 이 객체의 잠금으로 지킨다.
 */
final class InputLatencyProbe implements PaneMeter {
    static final Duration EXPIRY = Duration.ofMillis(500);
    // 셸이 답하지 않는 동안 쌓이는 키 · 보고 사이 표본의 상한 — 측정이 메모리를 늘리지 않게
    private static final int MAX_PENDING = 256;
    private static final int MAX_SAMPLES = 100_000;

    private final PaneId pane;
    private final LongSupplier nanos;
    private final long expiry = EXPIRY.toNanos();

    // 아래는 this 로 지킨다. pending 은 보낸 시각, 앞의 answered 개는 출력이 온 키다
    private final Deque<Long> pending = new ArrayDeque<>();
    private final List<Long> samples = new ArrayList<>();
    private int answered;

    InputLatencyProbe(PaneId pane, LongSupplier nanos) {
        this.pane = Objects.requireNonNull(pane, "pane");
        this.nanos = Objects.requireNonNull(nanos, "nanos");
    }

    @Override
    public synchronized void keyTyped() {
        long now = nanos.getAsLong();
        expire(now);
        if (pending.size() < MAX_PENDING) {
            pending.addLast(now);
        }
    }

    @Override
    public synchronized void output() {
        long now = nanos.getAsLong();
        expire(now);
        answered = pending.size();
    }

    @Override
    public synchronized void painted() {
        long now = nanos.getAsLong();
        expire(now);
        for (; answered > 0; answered--) {
            long sentAt = pending.removeFirst();
            if (samples.size() < MAX_SAMPLES) {
                samples.add(now - sentAt);
            }
        }
    }

    /** 지난 보고 뒤의 표본 한 줄. 표본이 없으면 empty. 보고하면 표본을 비운다. */
    synchronized Optional<String> report() {
        Optional<String> line = LatencyStats.of(samples).map(stats -> stats.line(pane));
        samples.clear();
        return line;
    }

    private void expire(long now) {
        while (!pending.isEmpty() && now - pending.peekFirst() > expiry) {
            pending.removeFirst();
            answered = Math.max(0, answered - 1);
        }
    }
}
