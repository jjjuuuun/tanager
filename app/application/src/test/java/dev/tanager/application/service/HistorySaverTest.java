package dev.tanager.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.tanager.application.port.in.AppEvent;
import dev.tanager.application.port.out.FakeScheduler;
import dev.tanager.application.port.out.HistoryStoreError;
import dev.tanager.application.port.out.InMemoryHistoryRepository;
import dev.tanager.application.port.out.LoadedHistory;
import dev.tanager.application.port.out.StoreAccess;
import dev.tanager.application.state.AppState;
import dev.tanager.application.state.HistoryLoaded;
import dev.tanager.application.state.HistoryStore;
import dev.tanager.application.state.ManualExecutor;
import dev.tanager.application.state.PendingVisit;
import dev.tanager.application.state.StateActor;
import dev.tanager.application.state.Transition;
import dev.tanager.domain.history.HistoryPolicy;
import dev.tanager.domain.history.ShellEnvironment;
import dev.tanager.domain.history.WorkHistory;
import dev.tanager.domain.history.WorkspacePath;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 첫 미저장 변경 후 1초에 저장하는 throttle(ADR-0013, #79). 시간은 {@link FakeScheduler} 로 손으로 진행하고 저장 실행기는 {@link
 * ManualExecutor} 로 결정적으로 돌린다.
 */
class HistorySaverTest {
    private static final WorkspacePath A =
            WorkspacePath.of(new ShellEnvironment.Windows(), "C:\\a").value().orElseThrow();
    private static final Duration SECOND = Duration.ofSeconds(1);

    /** 액터 · 저장 실행기가 모두 수동인 한 벌. */
    private static final class Rig {
        final ManualExecutor actorExecutor = new ManualExecutor();
        final ManualExecutor io = new ManualExecutor();
        final FakeScheduler scheduler = new FakeScheduler();
        final InMemoryHistoryRepository repository = new InMemoryHistoryRepository();
        final List<AppEvent> events = Collections.synchronizedList(new ArrayList<>());
        final StateActor actor;
        final HistorySaver saver;

        Rig(WorkHistory initial, HistoryStore store) {
            repository.load();
            actor = new StateActor(AppState.initial(initial).withStore(store), actorExecutor);
            actor.subscribe(events::add);
            saver = new HistorySaver(actor, repository, scheduler, io);
        }

        Rig(HistoryStore store) {
            this(WorkHistory.empty(HistoryPolicy.DEFAULT), store);
        }

        Rig() {
            this(new HistoryStore.Writable());
        }

        /** A 방문을 하나 더 기록하는 변경. 방문 횟수가 곧 변경 번호다. */
        void change() {
            actor.submit(current -> {
                WorkHistory next = current.history().recordVisit(A, Instant.EPOCH);
                return new Transition<>(current.withHistory(next), List.of(new AppEvent.HistoryChanged(next)), true);
            });
            settle();
        }

        /** VisitRecorder 처럼 A 방문을 기록하고, 불러오는 중이면 불러온 이력 위에 다시 적용되게 쌓아 둔다. */
        void visitQueued() {
            actor.submit(current -> {
                WorkHistory next = current.history().recordVisit(A, Instant.EPOCH);
                HistoryStore store = current.store() instanceof HistoryStore.Loading loading
                        ? loading.queued(new PendingVisit(A, Instant.EPOCH))
                        : current.store();
                return new Transition<>(
                        current.withHistory(next).withStore(store), List.of(new AppEvent.HistoryChanged(next)), true);
            });
            settle();
        }

        /** 파일에서 {@code fromFile} 을 쓸 수 있게 불러왔다. */
        void load(WorkHistory fromFile) {
            actor.submit(new HistoryLoaded(new LoadedHistory(fromFile, StoreAccess.WRITABLE, List.of())));
            settle();
        }

        /** A 를 고정하거나 푼다. 저장 실행기는 돌리지 않는다(다른 스레드가 저장 중일 수 있다). */
        void pin(boolean pinned) {
            actor.submit(current -> {
                WorkHistory next = current.history().pin(A, pinned).value().orElseThrow();
                return new Transition<>(current.withHistory(next), List.of(new AppEvent.HistoryChanged(next)), true);
            });
            actorExecutor.runAll();
        }

        /** 시간을 진행하고 그 결과로 생긴 일(저장 · 알림)을 모두 끝낸다. */
        void advance(Duration amount) {
            scheduler.advance(amount);
            settle();
        }

        void settle() {
            while (actorExecutor.pending() > 0 || io.pending() > 0) {
                actorExecutor.runAll();
                io.runAll();
            }
        }

        int changes() {
            return actor.snapshot().history().find(A).map(e -> e.visitCount()).orElse(0);
        }

        int saves() {
            return repository.saves().size();
        }

        int lastSavedChange() {
            List<WorkHistory> saves = repository.saves();
            return saves.isEmpty()
                    ? 0
                    : saves.get(saves.size() - 1).find(A).orElseThrow().visitCount();
        }

        List<String> notices() {
            synchronized (events) {
                return events.stream()
                        .filter(AppEvent.StoreNotice.class::isInstance)
                        .map(e -> ((AppEvent.StoreNotice) e).message())
                        .toList();
            }
        }
    }

    @Test
    @DisplayName("변경 1회 뒤 0.9초에는 저장하지 않고 1초가 되면 한 번 저장한다")
    void savesOnceOneSecondAfterFirstChange() {
        // given
        Rig rig = new Rig();
        rig.change();

        // when
        rig.advance(Duration.ofMillis(900));

        // then
        assertThat(rig.saves()).isZero();

        // when
        rig.advance(Duration.ofMillis(100));

        // then
        assertThat(rig.saves()).isEqualTo(1);
        assertThat(rig.lastSavedChange()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 1초 안의 여러 변경은 한 번의 저장에 모이고 저장은 그때의 최신 이력이다")
    void changesWithinOneSecondShareOneSaveOfLatestHistory() {
        // given
        Rig rig = new Rig();
        rig.change();
        rig.advance(Duration.ofMillis(400));
        rig.change();
        rig.advance(Duration.ofMillis(400));
        rig.change();

        // when
        rig.advance(Duration.ofMillis(200));

        // then
        assertThat(rig.saves()).isEqualTo(1);
        assertThat(rig.lastSavedChange()).isEqualTo(3);
    }

    @Test
    @DisplayName("0.3초마다 5초 동안 변경해도 1초마다 저장해 5번 안팎이고 어떤 변경도 1초 넘게 미저장으로 남지 않는다")
    void steadyChangesAreSavedEverySecondWithoutStarvation() {
        // given
        Rig rig = new Rig();
        List<Integer> changeSteps = new ArrayList<>();

        // when 100ms 단위로 6초 진행: 0.3초마다(k % 3 == 0) 5초가 되기 전까지 변경
        for (int k = 0; k <= 60; k++) {
            if (k > 0) {
                rig.advance(Duration.ofMillis(100));
            }
            if (k % 3 == 0 && k < 50) {
                rig.change();
                changeSteps.add(k);
            }
            // then 1초(10 스텝) 전에 만든 변경은 모두 저장돼 있다
            int step = k;
            long due = changeSteps.stream().filter(s -> s + 10 <= step).count();
            assertThat(rig.lastSavedChange()).as("%d00ms 시점", k).isGreaterThanOrEqualTo((int) due);
        }
        assertThat(rig.saves()).isBetween(4, 6);
        assertThat(rig.lastSavedChange()).isEqualTo(changeSteps.size());
    }

    @Property(tries = 50)
    @Label("변경 간격이 어떻든 저장 수는 변경 수를 넘지 않고, 변경은 1초 안에 저장되며, 끝에는 모두 저장돼 있다")
    void anyChangePatternIsSavedWithinOneSecond(
            @ForAll @Size(min = 1, max = 30) List<@IntRange(min = 0, max = 150) Integer> gapsInTensOfMillis) {
        // given
        Rig rig = new Rig();
        List<Integer> changeTimes = new ArrayList<>(); // 10ms 단위
        int[] gapsLeft = gapsInTensOfMillis.stream().mapToInt(Integer::intValue).toArray();
        int index = 0;
        int untilNext = gapsLeft[0];
        int total = gapsInTensOfMillis.stream().mapToInt(Integer::intValue).sum() + 100;

        // when
        for (int t = 0; t <= total; t++) {
            if (t > 0) {
                rig.advance(Duration.ofMillis(10));
            }
            while (index < gapsLeft.length && untilNext == 0) {
                rig.change();
                changeTimes.add(t);
                index++;
                if (index < gapsLeft.length) {
                    untilNext = gapsLeft[index];
                }
            }
            if (untilNext > 0) {
                untilNext--;
            }
            // then
            int now = t;
            long due = changeTimes.stream().filter(c -> c + 100 <= now).count();
            assertThat(rig.lastSavedChange()).isGreaterThanOrEqualTo((int) due);
        }
        assertThat(rig.saves()).isLessThanOrEqualTo(changeTimes.size());
        assertThat(rig.lastSavedChange()).isEqualTo(rig.changes());
    }

    @Test
    @DisplayName("저장이 느린 동안 변경이 생겨도 저장이 겹치지 않고 끝난 뒤 최신 이력이 저장된다")
    void changeDuringSlowSaveDoesNotOverlapAndLatestIsSavedAfter() throws Exception {
        // given 실제 스레드 실행기와 막히는 저장소
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        InMemoryHistoryRepository repository = new InMemoryHistoryRepository().willBlockSave(entered, release);
        repository.load();
        FakeScheduler scheduler = new FakeScheduler();
        StateActor actor = new StateActor(
                AppState.initial(WorkHistory.empty(HistoryPolicy.DEFAULT)).withStore(new HistoryStore.Writable()),
                StateActor.serialExecutor());
        try (var io = Executors.newVirtualThreadPerTaskExecutor()) {
            new HistorySaver(actor, repository, scheduler, io);
            Runnable change = () -> {
                int before = visitCount(actor);
                actor.submit(current -> {
                    WorkHistory next = current.history().recordVisit(A, Instant.EPOCH);
                    return new Transition<>(
                            current.withHistory(next), List.of(new AppEvent.HistoryChanged(next)), true);
                });
                await().until(() -> visitCount(actor) == before + 1);
            };
            change.run();
            scheduler.advance(SECOND);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            // when 첫 저장이 막힌 동안 변경하고 시간을 한참 진행
            change.run();
            scheduler.advance(Duration.ofSeconds(10));

            // then 두 번째 저장이 시작되지 않았다(단언이 실패해도 막힌 저장을 풀어 io 종료가 멈추지 않게 한다)
            try {
                assertThat(repository.saves()).hasSize(1);
                assertThat(repository.maxConcurrentSaves()).isEqualTo(1);
            } finally {
                // when 첫 저장이 끝난다
                release.countDown();
            }

            // then 끝난 뒤 최신 이력이 저장된다
            await().until(() -> {
                scheduler.advance(SECOND);
                return repository.saves().size() == 2;
            });
            assertThat(repository.saves().get(0).find(A).orElseThrow().visitCount())
                    .isEqualTo(1);
            assertThat(repository.saves().get(1).find(A).orElseThrow().visitCount())
                    .isEqualTo(2);
            assertThat(repository.maxConcurrentSaves()).isEqualTo(1);
        }
    }

    private static int visitCount(StateActor actor) {
        return actor.snapshot().history().find(A).map(e -> e.visitCount()).orElse(0);
    }

    @Test
    @DisplayName("불러오는 중에는 시간이 흘러도 저장하지 않는다(빈 이력으로 파일을 덮지 않는다)")
    void doesNotSaveWhileLoading() {
        // given
        Rig rig = new Rig(new HistoryStore.Loading(List.of()));
        rig.change();

        // when
        rig.advance(Duration.ofSeconds(5));

        // then
        assertThat(rig.saves()).isZero();
    }

    @Test
    @DisplayName("불러오는 동안 방문이 쌓였으면 불러온 뒤 1초가 지나면 합친 이력을 한 번 저장한다")
    void visitWhileLoadingIsSavedOneSecondAfterLoad() {
        // given
        Rig rig = new Rig(new HistoryStore.Loading(List.of()));
        rig.visitQueued();
        rig.advance(Duration.ofSeconds(5));
        assertThat(rig.saves()).isZero();

        // when
        rig.load(fileWithA(3));
        rig.advance(Duration.ofMillis(900));

        // then
        assertThat(rig.saves()).isZero();

        // when
        rig.advance(Duration.ofMillis(100));

        // then
        assertThat(rig.saves()).isEqualTo(1);
        assertThat(rig.lastSavedChange()).isEqualTo(4);
    }

    @Test
    @DisplayName("불러오는 동안 방문이 없었으면 불러온 이력은 파일과 같아 시간이 흘러도 flush 해도 저장하지 않는다")
    void loadWithoutPendingVisitsIsNeverSaved() {
        // given
        Rig rig = new Rig(new HistoryStore.Loading(List.of()));

        // when
        rig.load(fileWithA(3));
        rig.advance(Duration.ofSeconds(5));

        // then
        assertThat(rig.saves()).isZero();
        assertThat(rig.saver.flush(Duration.ofSeconds(2))).isTrue();
        assertThat(rig.saves()).isZero();
    }

    @Test
    @DisplayName("저장기를 만들기 전에 이미 쌓인 방문도 불러온 뒤 저장한다")
    void visitsQueuedBeforeSaverExistsAreSavedAfterLoad() {
        // given 저장기를 만들 때 이미 방문 하나가 쌓여 있다
        WorkHistory visited = WorkHistory.empty(HistoryPolicy.DEFAULT).recordVisit(A, Instant.EPOCH);
        Rig rig = new Rig(visited, new HistoryStore.Loading(List.of(new PendingVisit(A, Instant.EPOCH))));

        // when
        rig.load(fileWithA(3));
        rig.advance(SECOND);

        // then
        assertThat(rig.saves()).isEqualTo(1);
        assertThat(rig.lastSavedChange()).isEqualTo(4);
    }

    @Test
    @DisplayName("저장하는 동안 이력을 파일에 있던 값으로 되돌리면 저장이 끝난 뒤 되돌린 이력을 다시 저장한다")
    void revertingDuringSaveIsSavedAfterSaveEnds() throws Exception {
        // given A 를 방문해 저장했고(파일: 고정 안 됨), 고정한 이력을 저장하는 중이다
        Rig rig = new Rig();
        rig.change();
        rig.advance(SECOND);
        WorkHistory unpinned = rig.actor.snapshot().history();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        rig.repository.willBlockSave(entered, release);
        rig.pin(true);
        rig.scheduler.advance(SECOND);
        Thread saving = Thread.ofVirtual().start(rig.io::runAll);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        // when 저장 중에 고정을 풀고(처음 파일과 같다) 저장이 끝난다
        rig.pin(false);
        release.countDown();
        saving.join();
        rig.advance(SECOND);

        // then
        assertThat(rig.saves()).isEqualTo(3);
        assertThat(rig.repository.saves().get(2)).isEqualTo(unpinned);
    }

    private static WorkHistory fileWithA(int visits) {
        WorkHistory history = WorkHistory.empty(HistoryPolicy.DEFAULT);
        for (int i = 0; i < visits; i++) {
            history = history.recordVisit(A, Instant.EPOCH);
        }
        return history;
    }

    @Test
    @DisplayName("읽기 전용이면 변경해도 저장하지 않지만 이력과 HistoryChanged 는 갱신된다")
    void readOnlyDoesNotSaveButHistoryAndEventsUpdate() {
        // given
        Rig rig = new Rig(new HistoryStore.ReadOnly("다른 인스턴스가 쓰는 중"));

        // when
        rig.change();
        rig.advance(Duration.ofSeconds(5));

        // then
        assertThat(rig.saves()).isZero();
        assertThat(rig.changes()).isEqualTo(1);
        assertThat(rig.events).anyMatch(AppEvent.HistoryChanged.class::isInstance);
        assertThat(rig.saver.flush(Duration.ofSeconds(2))).isTrue();
        assertThat(rig.saves()).isZero();
    }

    @Test
    @DisplayName("저장이 연속 3번 실패해도 알림은 한 번이고, 성공한 뒤 다시 실패하면 한 번 더 알린다")
    void repeatedSameFailureIsNotifiedOnceAndAgainAfterSuccess() {
        // given
        Rig rig = new Rig();
        rig.repository.willFailSave(new HistoryStoreError.Io("디스크 가득"));

        // when 변경 때마다 시도하고 실패한다
        for (int i = 0; i < 3; i++) {
            rig.change();
            rig.advance(SECOND);
        }

        // then
        assertThat(rig.saves()).isEqualTo(3);
        assertThat(rig.notices()).hasSize(1);
        assertThat(rig.notices().get(0)).contains("디스크 가득");

        // when 성공했다가
        rig.repository.willSucceedSave();
        rig.change();
        rig.advance(SECOND);

        // then 알림 없이 저장된다
        assertThat(rig.notices()).hasSize(1);
        assertThat(rig.lastSavedChange()).isEqualTo(4);

        // when 다시 실패하면
        rig.repository.willFailSave(new HistoryStoreError.Io("디스크 가득"));
        rig.change();
        rig.advance(SECOND);

        // then 다시 한 번 알린다
        assertThat(rig.notices()).hasSize(2);
    }

    @Test
    @DisplayName("저장이 실패하면 시간이 흘러도 다시 시도하지 않고 다음 변경 때 다시 시도한다")
    void failedSaveIsRetriedOnNextChangeOnly() {
        // given
        Rig rig = new Rig();
        rig.repository.willFailSave(new HistoryStoreError.Io("잠김"));
        rig.change();
        rig.advance(SECOND);
        assertThat(rig.saves()).isEqualTo(1);

        // when 변경 없이 시간만 흐른다
        rig.advance(Duration.ofSeconds(30));

        // then
        assertThat(rig.saves()).isEqualTo(1);

        // when 다음 변경이 오고 저장소가 살아났다
        rig.repository.willSucceedSave();
        rig.change();
        rig.advance(SECOND);

        // then 실패했던 변경까지 포함한 최신 이력이 저장된다
        assertThat(rig.saves()).isEqualTo(2);
        assertThat(rig.lastSavedChange()).isEqualTo(2);
    }

    @Test
    @DisplayName("미저장 변경이 있을 때 flush 는 1초를 기다리지 않고 바로 저장하고 예약을 걷는다")
    void flushSavesUnsavedChangeImmediately() {
        // given
        Rig rig = new Rig();
        rig.change();
        rig.advance(Duration.ofMillis(100));

        // when
        boolean done = rig.saver.flush(Duration.ofSeconds(2));

        // then
        assertThat(done).isTrue();
        assertThat(rig.saves()).isEqualTo(1);
        assertThat(rig.lastSavedChange()).isEqualTo(1);
        assertThat(rig.scheduler.pendingCount()).isZero();

        // when 시간이 흘러도
        rig.advance(Duration.ofSeconds(5));

        // then 같은 변경을 다시 저장하지 않는다
        assertThat(rig.saves()).isEqualTo(1);
    }

    @Test
    @DisplayName("변경이 없으면 flush 는 저장하지 않는다")
    void flushWithoutChangeDoesNotSave() {
        // given
        Rig rig = new Rig();

        // when
        boolean done = rig.saver.flush(Duration.ofSeconds(2));

        // then
        assertThat(done).isTrue();
        assertThat(rig.saves()).isZero();
    }

    @Test
    @DisplayName("이미 저장한 변경만 있으면 flush 는 다시 저장하지 않는다")
    void flushAfterSaveDoesNotSaveAgain() {
        // given
        Rig rig = new Rig();
        rig.change();
        rig.advance(SECOND);

        // when
        rig.saver.flush(Duration.ofSeconds(2));

        // then
        assertThat(rig.saves()).isEqualTo(1);
    }

    @Test
    @DisplayName("불러오는 중에는 flush 도 저장하지 않는다")
    void flushWhileLoadingDoesNotSave() {
        // given
        Rig rig = new Rig(new HistoryStore.Loading(List.of()));
        rig.change();

        // when
        rig.saver.flush(Duration.ofSeconds(2));

        // then
        assertThat(rig.saves()).isZero();
    }

    @Test
    @DisplayName("진행 중인 저장이 시간 안에 끝나지 않으면 flush 는 기다리다 false 로 돌아온다")
    void flushGivesUpWhenInFlightSaveDoesNotFinishInTime() throws Exception {
        // given 첫 저장이 막혀 있다
        Rig rig = new Rig();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        rig.repository.willBlockSave(entered, release);
        rig.change();
        rig.scheduler.advance(SECOND);
        Thread blocked = Thread.ofVirtual().start(rig.io::runAll);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        // when
        long started = System.nanoTime();
        boolean done = rig.saver.flush(Duration.ofMillis(100));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        // then
        assertThat(done).isFalse();
        assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
        release.countDown();
        blocked.join();
    }
}
