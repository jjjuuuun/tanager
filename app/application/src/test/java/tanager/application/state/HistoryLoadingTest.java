package tanager.application.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.in.AppEvent;
import tanager.application.port.out.HistoryLoadNotice;
import tanager.application.port.out.HistoryStoreError;
import tanager.application.port.out.LoadedHistory;
import tanager.application.port.out.StoreAccess;
import tanager.application.service.VisitRecorder;
import tanager.domain.history.HistoryPolicy;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkHistory;
import tanager.domain.history.WorkspacePath;
import tanager.domain.session.IntegrationMode;
import tanager.domain.session.LaunchTemplate;
import tanager.domain.session.LiveSessions;
import tanager.domain.session.ProfileId;
import tanager.domain.session.ShellKind;
import tanager.domain.session.ShellProfile;
import tanager.domain.session.TerminalSession;
import tanager.domain.session.WorkingDir;
import tanager.domain.shared.PaneId;

/** 불러오기 전 방문을 쌓아 두었다가 불러온 이력 위에 다시 적용한다(ADR-0013, #79). */
class HistoryLoadingTest {
    private static final ShellEnvironment UBUNTU = new ShellEnvironment.Wsl("Ubuntu");
    private static final ShellProfile BASH = new ShellProfile(
            new ProfileId("wsl:Ubuntu"),
            "Ubuntu",
            ShellKind.BASH,
            UBUNTU,
            new LaunchTemplate("wsl.exe", List.of()),
            IntegrationMode.OSC7);
    private static final PaneId P1 = new PaneId("p1");
    private static final WorkspacePath A = wsl("/a");
    private static final WorkspacePath B = wsl("/b");
    private static final Instant LONG_AGO = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant T0 = Instant.parse("2026-11-27T00:00:00Z");

    private final ManualExecutor executor = new ManualExecutor();
    private final FakeClock clock = new FakeClock(T0);
    private final List<AppEvent> events = new ArrayList<>();
    private StateActor actor;
    private VisitRecorder visits;

    private static WorkspacePath wsl(String raw) {
        return WorkspacePath.of(UBUNTU, raw).value().orElseThrow();
    }

    private static WorkHistory empty() {
        return WorkHistory.empty(HistoryPolicy.DEFAULT);
    }

    /** 파일에 있던 이력: {@code path} 를 {@code count} 번 방문했고 첫 방문은 LONG_AGO. */
    private static WorkHistory fileWith(WorkspacePath path, int count) {
        WorkHistory history = empty();
        for (int i = 0; i < count; i++) {
            history = history.recordVisit(path, LONG_AGO.plusSeconds(i));
        }
        return history;
    }

    /** 앱 시작 직후: 이력은 비어 있고 아직 불러오지 않았다. 창 p1 의 셸이 떠 있다. */
    private void givenStartedBeforeLoad() {
        TerminalSession running = TerminalSession.start(BASH, new WorkingDir.Home())
                .value()
                .orElseThrow()
                .launched(10)
                .value()
                .orElseThrow();
        AppState initial =
                AppState.initial(empty()).withSessions(LiveSessions.empty().launched(P1, running));
        actor = new StateActor(initial, executor);
        actor.subscribe(events::add);
        visits = new VisitRecorder(actor, clock);
    }

    private void report(WorkspacePath path) {
        visits.record(P1, path);
        executor.runAll();
        clock.advance(Duration.ofSeconds(1));
    }

    private void loaded(LoadedHistory result) {
        actor.submit(new HistoryLoaded(result));
        executor.runAll();
    }

    private int count(WorkspacePath path) {
        return actor.snapshot().history().find(path).orElseThrow().visitCount();
    }

    @Test
    @DisplayName("앱 시작 상태는 불러오는 중이고 쌓인 방문이 없다")
    void initialStateIsLoadingWithNoPendingVisits() {
        // given / when
        AppState initial = AppState.initial(empty());

        // then
        assertThat(initial.store()).isEqualTo(new HistoryStore.Loading(List.of()));
    }

    @Test
    @DisplayName("불러오는 중의 방문은 메모리 이력에 들어가 트리에 보이고 방문 시각과 함께 쌓인다")
    void visitWhileLoadingGoesToMemoryHistoryAndIsQueued() {
        // given
        givenStartedBeforeLoad();

        // when
        report(A);

        // then
        assertThat(count(A)).isEqualTo(1);
        assertThat(actor.snapshot().store()).isEqualTo(new HistoryStore.Loading(List.of(new PendingVisit(A, T0))));
        assertThat(events).anyMatch(AppEvent.HistoryChanged.class::isInstance);
    }

    @Test
    @DisplayName("불러오는 중 /a 를 방문하고 불러온 이력에 /a(count 3)가 있으면 count 는 4 이고 쓸 수 있는 상태가 된다")
    void pendingVisitIsReplayedOnTopOfLoadedHistory() {
        // given
        givenStartedBeforeLoad();
        report(A);

        // when
        loaded(new LoadedHistory(fileWith(A, 3), StoreAccess.WRITABLE, List.of()));

        // then
        assertThat(count(A)).isEqualTo(4);
        assertThat(actor.snapshot().store()).isEqualTo(new HistoryStore.Writable());
    }

    @Test
    @DisplayName("재생한 방문은 보고된 시각을 마지막 방문으로 하고 불러온 첫 방문 시각은 그대로다")
    void replayKeepsReportedTimeAndLoadedFirstVisit() {
        // given
        givenStartedBeforeLoad();
        report(A);

        // when
        loaded(new LoadedHistory(fileWith(A, 3), StoreAccess.WRITABLE, List.of()));

        // then
        var entry = actor.snapshot().history().find(A).orElseThrow();
        assertThat(entry.lastVisited()).isEqualTo(T0);
        assertThat(entry.firstVisited()).isEqualTo(LONG_AGO);
    }

    @Test
    @DisplayName("불러온 이력에 없던 경로를 불러오는 중에 방문했으면 새 항목(count 1)이 되고 불러온 항목은 보존된다")
    void pendingVisitToNewPathIsAddedAndLoadedEntriesSurvive() {
        // given
        givenStartedBeforeLoad();
        report(B);

        // when
        loaded(new LoadedHistory(fileWith(A, 3), StoreAccess.WRITABLE, List.of()));

        // then
        assertThat(count(B)).isEqualTo(1);
        assertThat(count(A)).isEqualTo(3);
    }

    @Test
    @DisplayName("불러오는 중 같은 경로를 두 번 방문했으면 불러온 count 에 둘 다 더해진다")
    void repeatedPendingVisitsAreAllReplayed() {
        // given
        givenStartedBeforeLoad();
        report(A);
        report(B);
        report(A);

        // when
        loaded(new LoadedHistory(fileWith(A, 3), StoreAccess.WRITABLE, List.of()));

        // then
        assertThat(count(A)).isEqualTo(5);
        assertThat(count(B)).isEqualTo(1);
    }

    @Test
    @DisplayName("불러오면 재생한 이력을 HistoryChanged 로 알린다")
    void loadingPublishesMergedHistory() {
        // given
        givenStartedBeforeLoad();
        report(A);
        events.clear();

        // when
        loaded(new LoadedHistory(fileWith(A, 3), StoreAccess.WRITABLE, List.of()));

        // then
        assertThat(events).contains(new AppEvent.HistoryChanged(actor.snapshot().history()));
    }

    @Test
    @DisplayName("불러온 알림(복구 · 건너뜀 등)은 StoreNotice 로 하나씩 순서대로 알린다")
    void loadNoticesBecomeStoreNotices() {
        // given
        givenStartedBeforeLoad();
        HistoryLoadNotice skipped = new HistoryLoadNotice.EntriesSkipped(2);
        HistoryLoadNotice recovered = new HistoryLoadNotice.RecoveredFromBackup(java.util.Optional.empty());

        // when
        loaded(new LoadedHistory(empty(), StoreAccess.WRITABLE, List.of(recovered, skipped)));

        // then
        assertThat(events.stream()
                        .filter(AppEvent.StoreNotice.class::isInstance)
                        .map(AppEvent.StoreNotice.class::cast)
                        .map(AppEvent.StoreNotice::problem))
                .containsExactly(
                        new AppEvent.StoreProblem.Loaded(recovered), new AppEvent.StoreProblem.Loaded(skipped));
    }

    @Test
    @DisplayName("알림이 없으면 StoreNotice 도 없다")
    void noNoticesNoStoreNotice() {
        // given
        givenStartedBeforeLoad();

        // when
        loaded(new LoadedHistory(empty(), StoreAccess.WRITABLE, List.of()));

        // then
        assertThat(events).noneMatch(AppEvent.StoreNotice.class::isInstance);
    }

    @Test
    @DisplayName("읽기 전용으로 불러오면 ReadOnly 상태가 되고 이유에 알림 설명이 들어가며 쌓인 방문은 보이게 재생한다")
    void readOnlyLoadKeepsReasonAndStillReplaysVisits() {
        // given
        givenStartedBeforeLoad();
        report(A);
        HistoryLoadNotice locked = new HistoryLoadNotice.LockedByOtherInstance();

        // when
        loaded(new LoadedHistory(fileWith(A, 3), StoreAccess.READ_ONLY, List.of(locked)));

        // then
        assertThat(actor.snapshot().store()).isInstanceOf(HistoryStore.ReadOnly.class);
        assertThat(((HistoryStore.ReadOnly) actor.snapshot().store()).reason()).contains(locked.describe());
        assertThat(count(A)).isEqualTo(4);
    }

    @Test
    @DisplayName("불러오기가 실패하면 읽기 전용이 되어 오류를 알리고 방문은 메모리에 남는다")
    void loadFailureBecomesReadOnlyWithNotice() {
        // given
        givenStartedBeforeLoad();
        report(A);
        HistoryStoreError error = new HistoryStoreError.Io("권한 없음");

        // when
        actor.submit(new HistoryLoadFailed(error));
        executor.runAll();

        // then
        assertThat(actor.snapshot().store()).isInstanceOf(HistoryStore.ReadOnly.class);
        assertThat(count(A)).isEqualTo(1);
        assertThat(events)
                .anyMatch(e -> e instanceof AppEvent.StoreNotice n
                        && n.problem().equals(new AppEvent.StoreProblem.LoadFailed(error)));
    }

    @Test
    @DisplayName("쓸 수 있는 상태가 된 뒤의 방문은 쌓지 않고 상태도 그대로다")
    void visitAfterWritableIsNotQueued() {
        // given
        givenStartedBeforeLoad();
        loaded(new LoadedHistory(fileWith(A, 3), StoreAccess.WRITABLE, List.of()));

        // when
        report(A);

        // then
        assertThat(count(A)).isEqualTo(4);
        assertThat(actor.snapshot().store()).isEqualTo(new HistoryStore.Writable());
    }

    @Test
    @DisplayName("읽기 전용 상태의 방문도 메모리 이력과 HistoryChanged 로 트리에 반영된다")
    void visitWhileReadOnlyStillUpdatesHistoryAndPublishes() {
        // given
        givenStartedBeforeLoad();
        loaded(new LoadedHistory(fileWith(A, 3), StoreAccess.READ_ONLY, List.of()));
        events.clear();

        // when
        report(A);

        // then
        assertThat(count(A)).isEqualTo(4);
        assertThat(events).anyMatch(AppEvent.HistoryChanged.class::isInstance);
        assertThat(actor.snapshot().store()).isInstanceOf(HistoryStore.ReadOnly.class);
    }
}
