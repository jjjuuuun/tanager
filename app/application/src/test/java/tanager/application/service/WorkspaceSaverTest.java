package tanager.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.in.AppEvent;
import tanager.application.port.out.FakeScheduler;
import tanager.application.port.out.HistoryStoreError;
import tanager.application.port.out.InMemoryWorkspaceRepository;
import tanager.application.port.out.LoadedHistory;
import tanager.application.port.out.StoreAccess;
import tanager.application.state.AppState;
import tanager.application.state.HistoryLoaded;
import tanager.application.state.HistoryStore;
import tanager.application.state.ManualExecutor;
import tanager.application.state.StateActor;
import tanager.application.state.Transition;
import tanager.domain.history.HistoryPolicy;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkHistory;
import tanager.domain.history.WorkspacePath;
import tanager.domain.layout.Orientation;
import tanager.domain.session.IntegrationMode;
import tanager.domain.session.LaunchTemplate;
import tanager.domain.session.LiveSessions;
import tanager.domain.session.ProfileId;
import tanager.domain.session.ShellKind;
import tanager.domain.session.ShellProfile;
import tanager.domain.session.TerminalSession;
import tanager.domain.session.WorkingDir;
import tanager.domain.shared.PaneId;
import tanager.domain.workspace.PaneSpec;
import tanager.domain.workspace.WindowState;
import tanager.domain.workspace.Workspace;
import tanager.domain.workspace.WorkspaceSnapshot;

/**
 * 작업 공간을 첫 미저장 변경 후 1초에 저장한다(FR-RES-01, #179). 시간은 {@link FakeScheduler}, 저장 실행기는 {@link ManualExecutor} 로
 * 결정적으로 돌린다.
 */
class WorkspaceSaverTest {
    private static final Duration SECOND = Duration.ofSeconds(1);
    private static final PaneId P1 = new PaneId("p1");
    private static final ShellEnvironment UBUNTU = new ShellEnvironment.Wsl("Ubuntu");
    private static final ShellProfile BASH = new ShellProfile(
            new ProfileId("wsl:Ubuntu"),
            "Ubuntu",
            ShellKind.BASH,
            UBUNTU,
            new LaunchTemplate("wsl.exe", List.of()),
            IntegrationMode.OSC7);
    private static final WorkspacePath HOME_ME = path("/home/me");
    private static final WorkspacePath PROJECT = path("/home/me/project");

    private static WorkspacePath path(String raw) {
        return WorkspacePath.of(UBUNTU, raw).value().orElseThrow();
    }

    /** p1 하나를 /home/me 에서 bash 로 띄우는 저장된 작업 공간. */
    private static Workspace saved() {
        Workspace initial = Workspace.initial();
        return new Workspace(
                initial.tabs(),
                0,
                java.util.Map.of(P1, new PaneSpec(P1, Optional.of(BASH.id()), Optional.of(HOME_ME))),
                initial.nextPane(),
                initial.nextTab());
    }

    private static final class Rig {
        final ManualExecutor actorExecutor = new ManualExecutor();
        final ManualExecutor io = new ManualExecutor();
        final FakeScheduler scheduler = new FakeScheduler();
        final InMemoryWorkspaceRepository repository = new InMemoryWorkspaceRepository();
        final List<AppEvent> events = Collections.synchronizedList(new ArrayList<>());
        final StateActor actor;
        final WorkspaceSaver saver;

        Rig(Optional<WorkspaceSnapshot> loaded, HistoryStore store) {
            repository.loadWorkspace();
            Workspace start = loaded.map(WorkspaceSnapshot::workspace).orElseGet(Workspace::initial);
            actor = new StateActor(
                    AppState.initial(WorkHistory.empty(HistoryPolicy.DEFAULT), start)
                            .withStore(store),
                    actorExecutor);
            actor.subscribe(events::add);
            saver = new WorkspaceSaver(actor, repository, scheduler, io, loaded);
        }

        Rig(HistoryStore store) {
            this(Optional.of(new WorkspaceSnapshot(saved(), WindowState.DEFAULT)), store);
        }

        Rig() {
            this(new HistoryStore.Writable());
        }

        /** 포커스 창을 오른쪽으로 나눈다(LayoutChanged). */
        void split() {
            actor.submit(current -> {
                Workspace next = current.workspace()
                        .split(Orientation.ROW, Optional.empty(), Optional.empty())
                        .workspace();
                return new Transition<>(current.withWorkspace(next), List.of(new AppEvent.LayoutChanged(next)), true);
            });
            settle();
        }

        /** p1 에 bash 가 /home/me 에서 떴다(SessionsChanged). */
        void launched() {
            actor.submit(current -> {
                TerminalSession running = TerminalSession.start(BASH, new WorkingDir.At(HOME_ME))
                        .value()
                        .orElseThrow()
                        .launched(42)
                        .value()
                        .orElseThrow();
                LiveSessions next = current.sessions().launched(P1, running);
                return new Transition<>(current.withSessions(next), List.of(new AppEvent.SessionsChanged(next)), true);
            });
            settle();
        }

        /** p1 의 셸이 {@code cwd} 를 알렸다(SessionCwdChanged). */
        void reported(WorkspacePath cwd) {
            actor.submit(current -> {
                LiveSessions next = current.sessions().cwdReported(P1, cwd).orElseThrow();
                return new Transition<>(
                        current.withSessions(next), List.of(new AppEvent.SessionCwdChanged(P1, cwd)), true);
            });
            settle();
        }

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

        List<AppEvent> notices() {
            synchronized (events) {
                return events.stream()
                        .filter(AppEvent.StoreNotice.class::isInstance)
                        .toList();
            }
        }
    }

    @Test
    @DisplayName("창을 나누면 1초가 지날 때 작업 공간을 한 번 저장한다")
    void splitIsSavedOnceAfterOneSecond() {
        // given
        Rig rig = new Rig();

        // when
        rig.split();
        rig.advance(Duration.ofMillis(999));
        int beforeDeadline = rig.repository.saves().size();
        rig.advance(Duration.ofMillis(1));
        rig.advance(Duration.ofSeconds(5));

        // then
        assertThat(beforeDeadline).isZero();
        assertThat(rig.repository.saves()).hasSize(1);
        assertThat(rig.repository.saves().getFirst().workspace().tab().root().panes())
                .containsExactly(P1, new PaneId("p2"));
    }

    @Test
    @DisplayName("0.2초마다 10번 바꾸면 2초 동안 1초마다 한 번 이상 저장되고 동시에 두 저장이 돌지 않는다")
    void steadyChangesAreSavedEverySecondOneAtATime() {
        // given
        Rig rig = new Rig();
        int maxInFlight = 0;
        List<Integer> savedBy = new ArrayList<>();

        // when 저장 실행기는 0.2초마다 한 번만 돌린다 — 그 사이 쌓인 저장 수가 동시에 돌 저장 수다
        for (int i = 1; i <= 10; i++) {
            rig.actor.submit(current -> {
                Workspace next = current.workspace()
                        .split(Orientation.ROW, Optional.empty(), Optional.empty())
                        .workspace();
                return new Transition<>(current.withWorkspace(next), List.of(new AppEvent.LayoutChanged(next)), true);
            });
            rig.actorExecutor.runAll();
            rig.scheduler.advance(Duration.ofMillis(200));
            maxInFlight = Math.max(maxInFlight, rig.io.pending());
            rig.settle();
            if (i % 5 == 0) {
                savedBy.add(rig.repository.saves().size());
            }
        }

        // then
        assertThat(maxInFlight).isLessThanOrEqualTo(1);
        assertThat(savedBy.get(0)).isGreaterThanOrEqualTo(1);
        assertThat(savedBy.get(1)).isGreaterThanOrEqualTo(2);
        assertThat(rig.repository.saves().getLast().workspace().specs()).hasSize(11);
    }

    @Test
    @DisplayName("셸이 OSC 로 cwd 를 알리면 1초 뒤 저장된 그 창의 lastCwd 가 새 경로다")
    void reportedCwdIsSaved() {
        // given
        Rig rig = new Rig();
        rig.launched();

        // when
        rig.reported(PROJECT);
        rig.advance(SECOND);

        // then
        assertThat(rig.repository.saves()).hasSize(1);
        assertThat(rig.repository
                        .saves()
                        .getFirst()
                        .workspace()
                        .spec(P1)
                        .orElseThrow()
                        .dir())
                .contains(PROJECT);
    }

    @Test
    @DisplayName("저장된 명세 그대로 셸이 뜨기만 하면 바뀐 것이 없어 쓰지 않는다")
    void launchMatchingSpecIsNotAChange() {
        // given
        Rig rig = new Rig();

        // when
        rig.launched();
        rig.advance(Duration.ofSeconds(3));

        // then
        assertThat(rig.repository.saves()).isEmpty();
        assertThat(rig.scheduler.pendingCount()).isZero();
    }

    @Test
    @DisplayName("저장소가 READ_ONLY 면 바꿔도 쓰지 않고 저장 알림도 내지 않는다")
    void readOnlyStoreNeverWrites() {
        // given
        Rig rig = new Rig(new HistoryStore.ReadOnly("새 버전이 쓴 파일"));

        // when
        rig.split();
        rig.advance(Duration.ofSeconds(3));
        boolean flushed = rig.saver.flush(SECOND);

        // then
        assertThat(flushed).isTrue();
        assertThat(rig.repository.saves()).isEmpty();
        assertThat(rig.notices()).isEmpty();
    }

    @Test
    @DisplayName("아무것도 바꾸지 않으면 종료 flush 가 쓰지 않는다")
    void flushWithoutChangesWritesNothing() {
        // given
        Rig rig = new Rig();

        // when
        boolean flushed = rig.saver.flush(SECOND);

        // then
        assertThat(flushed).isTrue();
        assertThat(rig.repository.saves()).isEmpty();
    }

    @Test
    @DisplayName("종료 flush 는 예약을 거두고 미저장 변경을 부른 스레드에서 바로 쓴다")
    void flushWritesPendingChangeAtOnce() {
        // given
        Rig rig = new Rig();
        rig.split();

        // when
        boolean flushed = rig.saver.flush(SECOND);

        // then
        assertThat(flushed).isTrue();
        assertThat(rig.repository.saves()).hasSize(1);
        assertThat(rig.scheduler.pendingCount()).isZero();
    }

    @Test
    @DisplayName("불러오는 중의 변경은 불러온 뒤 1초 안에 저장한다")
    void changeWhileLoadingIsSavedAfterLoad() {
        // given
        Rig rig = new Rig(new HistoryStore.Loading(List.of()));
        rig.split();
        rig.advance(Duration.ofSeconds(2));
        assertThat(rig.repository.saves()).isEmpty();

        // when
        rig.actor.submit(new HistoryLoaded(
                new LoadedHistory(WorkHistory.empty(HistoryPolicy.DEFAULT), StoreAccess.WRITABLE, List.of())));
        rig.settle();
        rig.advance(SECOND);

        // then
        assertThat(rig.repository.saves()).hasSize(1);
    }

    @Test
    @DisplayName("저장한 작업 공간이 없으면 첫 변경을 저장하고, 창 상태는 기본값을 쓴다")
    void withoutSavedWorkspaceFirstChangeIsSaved() {
        // given
        Rig rig = new Rig(Optional.empty(), new HistoryStore.Writable());

        // when
        rig.split();
        rig.advance(SECOND);

        // then
        assertThat(rig.repository.saves()).hasSize(1);
        assertThat(rig.repository.saves().getFirst().window()).isEqualTo(WindowState.DEFAULT);
    }

    @Test
    @DisplayName("불러온 창 상태는 그대로 다시 쓴다(창 상태 저장은 #180)")
    void loadedWindowIsKept() {
        // given
        WindowState window = new WindowState(
                Optional.empty(),
                true,
                new tanager.domain.workspace.PanelState(false, 300),
                new tanager.domain.workspace.PanelState(true, 264));
        Rig rig = new Rig(Optional.of(new WorkspaceSnapshot(saved(), window)), new HistoryStore.Writable());

        // when
        rig.split();
        rig.advance(SECOND);

        // then
        assertThat(rig.repository.saves().getFirst().window()).isEqualTo(window);
    }

    @Test
    @DisplayName("저장이 실패하면 상태줄에 한 번 알리고 같은 오류가 이어지면 다시 알리지 않는다")
    void failureIsAnnouncedOnce() {
        // given
        Rig rig = new Rig();
        HistoryStoreError error = new HistoryStoreError.Io("디스크 꽉 참");
        rig.repository.willFailSave(error);

        // when
        rig.split();
        rig.advance(SECOND);
        rig.split();
        rig.advance(SECOND);

        // then
        assertThat(rig.repository.saves()).hasSize(2);
        assertThat(rig.notices())
                .containsExactly(new AppEvent.StoreNotice(new AppEvent.StoreProblem.SaveFailed(error)));
    }
}
