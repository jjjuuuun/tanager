package dev.tanager.application.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.tanager.application.port.in.AppEvent;
import dev.tanager.domain.history.HistoryPolicy;
import dev.tanager.domain.history.ShellEnvironment;
import dev.tanager.domain.history.WorkHistory;
import dev.tanager.domain.history.WorkspacePath;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StateActorConcurrencyTest {
    private static final WorkspacePath HOME =
            WorkspacePath.of(new ShellEnvironment.Windows(), "C:\\work").value().orElseThrow();

    private static Command<Integer> visit() {
        return current -> {
            WorkHistory next = current.history().recordVisit(HOME, Instant.EPOCH);
            return new Transition<>(
                    current.withHistory(next),
                    List.of(new AppEvent.HistoryChanged(next)),
                    next.entries().get(HOME).visitCount());
        };
    }

    private static StateActor actorOn(ExecutorService serial) {
        return new StateActor(AppState.initial(WorkHistory.empty(HistoryPolicy.DEFAULT)), serial);
    }

    @Test
    @DisplayName("두 스레드가 방문 명령을 1000번씩 내도 count 합이 2000 이다")
    void noLostUpdates() throws Exception {
        // given
        ExecutorService serial = Executors.newSingleThreadExecutor();
        ExecutorService producers = Executors.newFixedThreadPool(2);
        try {
            StateActor actor = actorOn(serial);
            var replies = new CompletableFuture<?>[2];

            // when
            for (int t = 0; t < 2; t++) {
                replies[t] = CompletableFuture.runAsync(
                        () -> {
                            for (int i = 0; i < 1000; i++) {
                                actor.submit(visit());
                            }
                        },
                        producers);
            }
            CompletableFuture.allOf(replies).get();

            // then
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> assertThat(actor.snapshot()
                                    .history()
                                    .entries()
                                    .get(HOME)
                                    .visitCount())
                            .isEqualTo(2000));
        } finally {
            serial.shutdownNow();
            producers.shutdownNow();
        }
    }

    @Test
    @DisplayName("실행 중 다른 스레드가 읽는 snapshot 은 항상 확정된 상태이고 거꾸로 가지 않는다")
    void snapshotIsAlwaysCommitted() throws Exception {
        // given
        ExecutorService serial = Executors.newSingleThreadExecutor();
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            StateActor actor = actorOn(serial);
            var stop = new AtomicBoolean();
            var regressions = new AtomicInteger();
            var reads = new AtomicInteger();
            reader.execute(() -> {
                int last = 0;
                while (!stop.get()) {
                    var found = actor.snapshot().history().entries().get(HOME);
                    int now = found == null ? 0 : found.visitCount();
                    if (now < last) {
                        regressions.incrementAndGet();
                    }
                    last = now;
                    reads.incrementAndGet();
                }
            });

            // when
            CompletableFuture<?> last = null;
            for (int i = 0; i < 2000; i++) {
                last = actor.submit(visit()).toCompletableFuture();
            }
            last.get();
            stop.set(true);

            // then
            await().atMost(Duration.ofSeconds(10)).until(() -> reads.get() > 0);
            assertThat(regressions).hasValue(0);
            assertThat(actor.snapshot().history().entries().get(HOME).visitCount())
                    .isEqualTo(2000);
        } finally {
            serial.shutdownNow();
            reader.shutdownNow();
        }
    }

    @Test
    @DisplayName("명령과 구독자는 제출한 스레드가 아니라 실행기의 한 스레드에서 돈다")
    void commandsAndListenersRunOnExecutorThread() throws Exception {
        // given
        ExecutorService serial = Executors.newSingleThreadExecutor(r -> new Thread(r, "tanager-state-test"));
        try {
            StateActor actor = actorOn(serial);
            var listenerThread = new AtomicReference<String>();
            var commandThread = new AtomicReference<String>();
            actor.subscribe(e -> listenerThread.set(Thread.currentThread().getName()));

            // when
            actor.submit(current -> {
                        commandThread.set(Thread.currentThread().getName());
                        return visit().apply(current);
                    })
                    .toCompletableFuture()
                    .get();

            // then
            assertThat(commandThread).hasValue("tanager-state-test");
            assertThat(listenerThread).hasValue("tanager-state-test");
        } finally {
            serial.shutdownNow();
        }
    }

    @Property
    @Label("임의 개수의 방문 명령을 순서대로 내면 최종 count 와 이벤트 count 열이 1..n 이다")
    void eventsFollowSubmissionOrder(@ForAll @IntRange(min = 1, max = 50) int n) {
        // given
        ManualExecutor executor = new ManualExecutor();
        StateActor actor = new StateActor(AppState.initial(WorkHistory.empty(HistoryPolicy.DEFAULT)), executor);
        var counts = new ArrayList<Integer>();
        actor.subscribe(e -> counts.add(
                ((AppEvent.HistoryChanged) e).history().entries().get(HOME).visitCount()));

        // when
        for (int i = 0; i < n; i++) {
            actor.submit(visit());
        }
        executor.runAll();

        // then
        assertThat(counts)
                .containsExactlyElementsOf(
                        java.util.stream.IntStream.rangeClosed(1, n).boxed().toList());
    }
}
