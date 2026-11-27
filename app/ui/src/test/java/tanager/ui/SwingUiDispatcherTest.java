package tanager.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SwingUiDispatcherTest {

    @Test
    @DisplayName("다른 스레드에서 넘긴 일은 EDT 에서 넘긴 순서대로 돈다")
    void runsOnEdtInOrder() throws Exception {
        // given
        var dispatcher = new SwingUiDispatcher();
        var order = new ArrayList<Integer>();
        var onEdt = new ArrayList<Boolean>();
        var done = new CountDownLatch(3);

        // when
        for (int i = 1; i <= 3; i++) {
            int n = i;
            dispatcher.dispatch(() -> {
                onEdt.add(SwingUtilities.isEventDispatchThread());
                order.add(n);
                done.countDown();
            });
        }

        // then
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        SwingUtilities.invokeAndWait(() -> {
            assertThat(order).containsExactly(1, 2, 3);
            assertThat(onEdt).containsOnly(true);
        });
    }

    @Test
    @DisplayName("EDT 안에서 넘긴 일도 바로 돌지 않고 지금 일이 끝난 뒤에 돈다")
    void deferredEvenOnEdt() throws Exception {
        // given
        var dispatcher = new SwingUiDispatcher();
        List<String> log = new ArrayList<>();
        var done = new CountDownLatch(1);

        // when
        SwingUtilities.invokeAndWait(() -> {
            dispatcher.dispatch(() -> {
                log.add("dispatched");
                done.countDown();
            });
            log.add("caller");
        });

        // then
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        SwingUtilities.invokeAndWait(() -> assertThat(log).containsExactly("caller", "dispatched"));
    }
}
