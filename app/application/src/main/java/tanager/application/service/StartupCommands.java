package tanager.application.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import tanager.application.port.in.AppEvent;
import tanager.application.port.in.RecordVisitUseCase;
import tanager.application.port.in.StartNotice;
import tanager.application.port.in.StartNotice.StartupCommandSkipped.Reason;
import tanager.application.port.out.Cancellable;
import tanager.application.port.out.Scheduler;
import tanager.application.port.out.TerminalStream;
import tanager.application.state.StateActor;
import tanager.application.state.Transition;
import tanager.domain.history.WorkspacePath;
import tanager.domain.session.ShellLine;
import tanager.domain.session.TerminalSession;
import tanager.domain.shared.PaneId;

/**
 * 새 창의 첫 셸에 한 번 입력할 시작 명령(FR-TK-09, #216 · ADR-0020). 창 ID → (요청한 폴더, 명령)의 메모리 표다 — {@code PaneSpec} ·
 * state.json 에 넣지 않으므로 복원 때 다시 실행하지 않는다. 창의 첫 셸이 뜰 때 한 번 꺼내 그 셸에만 단다(재시작에는 달지 않는다).
 *
 * <ul>
 *   <li>셸 통합으로 경로를 추적하면 첫 보고(= 첫 프롬프트)를 기다려, 그 경로가 요청한 폴더와 같을 때만 쓴다. {@link #FIRST_REPORT_TIMEOUT}(10초) 안에
 *       보고가 없으면 버린다.
 *   <li>추적하지 않으면(통합 없음 · 모드 NONE) 띄운 경로가 그 폴더일 때 {@link #UNTRACKED_DELAY} 뒤에 쓴다 — rc · 프로필을 읽는 동안
 *       쓰면 입력이 먹히거나 두 번 보인다.
 *   <li>셸이 먼저 끝나면 버린다. 창을 닫아 끝났으면 알리지 않는다.
 * </ul>
 *
 * <p>쓰기는 I/O 실행기에서 한다 — {@link TerminalStream#write} 는 블로킹될 수 있고 첫 보고는 PTY 읽기 스레드에서 온다. 표와 각 셸의
 * 결정 상태는 각자의 모니터 하나로 지킨다(java-style 8 예외: 출력 포트 일정 조율 상태).
 */
public final class StartupCommands {
    /** 첫 보고를 기다리는 한도. */
    static final Duration FIRST_REPORT_TIMEOUT = Duration.ofSeconds(10);
    /** 경로를 추적하지 않는 셸에서 띄운 뒤 쓰기까지 기다리는 시간. */
    static final Duration UNTRACKED_DELAY = Duration.ofMillis(1_500);

    private static final String ENTER = "\r";

    private final Scheduler scheduler;
    private final Executor io;
    private final StateActor state;
    private final Map<PaneId, Pending> pending = new HashMap<>();

    /**
     * @param scheduler 첫 보고 시간 제한 · 추적하지 않는 셸의 지연 쓰기
     * @param io 셸에 쓰는 실행기(블로킹 가능 — 가상 스레드)
     * @param state 건너뛴 알림({@link AppEvent.SessionNotice})을 낸다
     */
    public StartupCommands(Scheduler scheduler, Executor io, StateActor state) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.io = Objects.requireNonNull(io, "io");
        this.state = Objects.requireNonNull(state, "state");
    }

    /** 요청한 폴더와 쓸 명령. */
    private record Pending(WorkspacePath folder, ShellLine line) {}

    /** 창 {@code pane} 의 첫 셸에 {@code line} 을 달아 둔다. 셸이 {@code folder} 에서 떠야 쓴다. */
    synchronized void attach(PaneId pane, WorkspacePath folder, ShellLine line) {
        pending.put(
                Objects.requireNonNull(pane, "pane"),
                new Pending(Objects.requireNonNull(folder, "folder"), Objects.requireNonNull(line, "line")));
    }

    private synchronized Optional<Pending> take(PaneId pane) {
        return Optional.ofNullable(pending.remove(pane));
    }

    /**
     * 창 {@code pane} 의 첫 셸이 떴다 — {@code stream.start()} 전에 부른다. 달린 명령이 없으면 {@code visits} 를 그대로 돌려준다. 있으면 그
     * 셸에 달고, {@code tracked} 면 첫 보고를 가로채 보는 {@code visits} 감싸개를 돌려준다(cwd 추적이 그것으로 기록한다).
     *
     * @param tracked 셸 통합이 이 셸의 경로를 알려 준다
     */
    RecordVisitUseCase launched(
            PaneId pane, TerminalSession running, TerminalStream stream, boolean tracked, RecordVisitUseCase visits) {
        Optional<Pending> taken = take(pane);
        if (taken.isEmpty()) {
            return visits;
        }
        Armed armed = new Armed(pane, taken.get(), stream);
        stream.onExit().thenAccept(code -> armed.exited());
        if (!tracked) {
            if (running.knownCwd().filter(taken.get().folder()::equals).isPresent()) {
                armed.after(UNTRACKED_DELAY, armed::fire);
            } else {
                armed.skip();
            }
            return visits;
        }
        armed.after(FIRST_REPORT_TIMEOUT, armed::timedOut);
        return (reporting, cwd) -> {
            armed.reported(cwd);
            visits.record(reporting, cwd);
        };
    }

    /** 셸 하나에 단 명령. 쓰기 · 버리기는 한 번만 정해진다. */
    private final class Armed {
        private final PaneId pane;
        private final Pending command;
        private final TerminalStream stream;
        private boolean decided;
        private Cancellable timer = () -> {};

        Armed(PaneId pane, Pending command, TerminalStream stream) {
            this.pane = pane;
            this.command = command;
            this.stream = stream;
        }

        synchronized void after(Duration delay, Runnable task) {
            if (!decided) {
                timer = scheduler.schedule(delay, task);
            }
        }

        // 처음 부른 쪽만 true. 남은 예약은 거둔다
        private synchronized boolean decide() {
            if (decided) {
                return false;
            }
            decided = true;
            timer.cancel();
            return true;
        }

        void reported(WorkspacePath cwd) {
            if (!decide()) {
                return;
            }
            if (cwd.equals(command.folder())) {
                write();
            } else {
                notice(Reason.PATH_MISMATCH);
            }
        }

        void fire() {
            if (decide()) {
                write();
            }
        }

        void skip() {
            if (decide()) {
                notice(Reason.PATH_MISMATCH);
            }
        }

        void timedOut() {
            if (decide()) {
                notice(Reason.TIMED_OUT);
            }
        }

        // 창을 닫아 끝났으면(작업 공간에 그 창이 없으면) 알리지 않는다
        void exited() {
            if (!decide()) {
                return;
            }
            StartNotice notice = new StartNotice.StartupCommandSkipped(command.line(), Reason.SHELL_EXITED);
            state.submit(current -> current.workspace().spec(pane).isPresent()
                    ? new Transition<>(current, List.of(new AppEvent.SessionNotice(pane, notice)), true)
                    : new Transition<>(current, List.of(), false));
        }

        private void write() {
            byte[] bytes = (command.line().text() + ENTER).getBytes(StandardCharsets.UTF_8);
            io.execute(() -> stream.write(bytes));
        }

        private void notice(Reason reason) {
            StartNotice notice = new StartNotice.StartupCommandSkipped(command.line(), reason);
            state.submit(current -> new Transition<>(current, List.of(new AppEvent.SessionNotice(pane, notice)), true));
        }
    }
}
