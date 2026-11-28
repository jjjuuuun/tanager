package tanager.ui.measure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** EDT 정지 감시(NFR-01 · NFR-10, #222). 50ms 주기 타이머가 늦게 온 만큼이 지연이다. 시계는 가짜다. */
class EdtStallWatchTest {
    private final AtomicLong nanos = new AtomicLong();
    private final List<String> log = new ArrayList<>();
    private final StackTraceElement[] stack = {
        frame("a.Slow", "parse"),
        frame("a.Slow", "read"),
        frame("a.Panel", "paint"),
        frame("a.Panel", "paintAll"),
        frame("javax.swing.RepaintManager", "paint"),
        frame("java.awt.EventQueue", "dispatch")
    };
    private final EdtStallWatch watch = new EdtStallWatch(nanos::get, () -> stack, log::add);

    @Test
    @DisplayName("타이머가 150ms 늦게 오면 정지 로그 한 줄을 남긴다")
    void ticked_delay150ms_logsOneLine() {
        // given
        watch.ticked();
        advance(50 + 150);

        // when
        watch.ticked();

        // then
        assertThat(log).containsExactly("edt-stall delay=150ms stack=-");
    }

    @Test
    @DisplayName("지연이 100ms 이하이면 남기지 않는다")
    void ticked_delay100ms_logsNothing() {
        // given
        watch.ticked();
        advance(50 + 100);

        // when
        watch.ticked();
        advance(50);
        watch.ticked();

        // then
        assertThat(log).isEmpty();
    }

    @Test
    @DisplayName("멈춰 있는 동안 감시 스레드가 본 EDT 스택 상위 5줄을 같이 남긴다")
    void ticked_afterWatcherSawStall_logsTopFiveFrames() {
        // given
        watch.ticked();
        advance(120);
        watch.watch(); // 아직 100ms 를 넘지 않았다 — 스택을 읽지 않는다
        advance(40);
        watch.watch();
        advance(40);

        // when
        watch.ticked();

        // then
        assertThat(log)
                .containsExactly("edt-stall delay=150ms stack=a.Slow.parse(Slow.java:1) < a.Slow.read(Slow.java:1)"
                        + " < a.Panel.paint(Panel.java:1) < a.Panel.paintAll(Panel.java:1)"
                        + " < javax.swing.RepaintManager.paint(RepaintManager.java:1)");
    }

    @Test
    @DisplayName("첫 박자는 기준만 잡는다 — 감시를 켠 뒤 처음 온 타이머는 지연이 아니다")
    void ticked_first_logsNothing() {
        // given
        advance(10_000);

        // when
        watch.ticked();

        // then
        assertThat(log).isEmpty();
    }

    private void advance(long millis) {
        nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
    }

    private static StackTraceElement frame(String type, String method) {
        String file = type.substring(type.lastIndexOf('.') + 1) + ".java";
        return new StackTraceElement(type, method, file, 1);
    }
}
