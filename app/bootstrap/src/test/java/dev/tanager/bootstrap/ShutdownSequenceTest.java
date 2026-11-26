package dev.tanager.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.tanager.application.port.in.AppEvent;
import dev.tanager.application.port.out.FakeScheduler;
import dev.tanager.application.port.out.InMemoryHistoryRepository;
import dev.tanager.application.service.HistorySaver;
import dev.tanager.application.state.AppState;
import dev.tanager.application.state.HistoryStore;
import dev.tanager.application.state.StateActor;
import dev.tanager.application.state.Transition;
import dev.tanager.domain.history.HistoryPolicy;
import dev.tanager.domain.history.ShellEnvironment;
import dev.tanager.domain.history.WorkHistory;
import dev.tanager.domain.history.WorkspacePath;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 종료 순서(ADR-0011 개정): 미저장 이력을 쓰지 못했으면 저장소를 닫지 않아 막힌 저장에 종료가 붙잡히지 않는다. */
class ShutdownSequenceTest {
    private static final WorkspacePath A =
            WorkspacePath.of(new ShellEnvironment.Windows(), "C:\\a").value().orElseThrow();

    private final InMemoryHistoryRepository repository = new InMemoryHistoryRepository();
    private final ExecutorService stateExecutor = StateActor.serialExecutor();
    private final StateActor state = new StateActor(
            AppState.initial(WorkHistory.empty(HistoryPolicy.DEFAULT)).withStore(new HistoryStore.Writable()),
            stateExecutor);
    private final FakeScheduler scheduler = new FakeScheduler();
    private final AtomicInteger timersStopped = new AtomicInteger();
    private final AtomicInteger storeClosed = new AtomicInteger();

    private void change() {
        state.submit(current -> {
            WorkHistory next = current.history().recordVisit(A, Instant.EPOCH);
            return new Transition<>(current.withHistory(next), List.of(new AppEvent.HistoryChanged(next)), true);
        });
        await().atMost(Duration.ofSeconds(5)).until(() -> scheduler.pendingCount() == 1);
    }

    @Test
    @DisplayName("진행 중인 저장이 끝나지 않아 flush 가 시간을 넘기면 저장소를 닫지 않고 기한 안에 돌아온다")
    void blockedSaveSkipsClosingStore() throws Exception {
        // given 저장이 막혀 있다
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        repository.willBlockSave(entered, release).load();
        try (ExecutorService io = Executors.newVirtualThreadPerTaskExecutor()) {
            HistorySaver saver = new HistorySaver(state, repository, scheduler, io);
            change();
            scheduler.advance(Duration.ofSeconds(1));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                // when
                long started = System.nanoTime();
                Assembly.shutdownInOrder(
                        stateExecutor,
                        saver,
                        Duration.ofMillis(200),
                        timersStopped::incrementAndGet,
                        storeClosed::incrementAndGet);
                Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

                // then
                assertThat(storeClosed).hasValue(0);
                assertThat(timersStopped).hasValue(1);
                assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    @DisplayName("미저장 이력을 저장했으면 저장소를 한 번 닫는다")
    void savedFlushClosesStore() throws Exception {
        // given
        repository.load();
        try (ExecutorService io = Executors.newVirtualThreadPerTaskExecutor()) {
            HistorySaver saver = new HistorySaver(state, repository, scheduler, io);
            change();

            // when
            Assembly.shutdownInOrder(
                    stateExecutor,
                    saver,
                    Duration.ofSeconds(2),
                    timersStopped::incrementAndGet,
                    storeClosed::incrementAndGet);

            // then
            assertThat(repository.saves()).hasSize(1);
            assertThat(storeClosed).hasValue(1);
            assertThat(timersStopped).hasValue(1);
        }
    }
}
