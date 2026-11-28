package tanager.application.service;

import java.lang.System.Logger.Level;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import tanager.application.port.in.AppEvent;
import tanager.application.port.in.CheckMissingPathsUseCase;
import tanager.application.port.out.DirectoryProbe;
import tanager.application.state.AppState;
import tanager.application.state.MissingPathChecked;
import tanager.application.state.StateActor;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;

/**
 * 이력 경로가 아직 있는지 백그라운드로 확인해 사라진 경로를 상태에 알린다(FR-HIS-08, #186). 결과는 저장하지 않는다.
 *
 * <ul>
 *   <li>언제: 이력이 바뀌면(불러오기 = 시작 뒤 한 번 포함) · 세션이 바뀌면 아직 확인하지 않은 항목만, 창 활성화 때는 마지막 확인에서
 *       {@link #STALE_AFTER} 가 지난 항목도.
 *   <li>무엇을: Windows 경로는 늘. WSL 경로는 그 배포판에 실행 중 세션이 있을 때만 — {@code \\wsl.localhost} 확인이 VM 을 깨우기
 *       때문이다(FR-MON-02 와 같은 원칙, {@link DirectoryProbe}). 세션이 뜨면 다음 스캔에서 확인한다.
 *   <li>어떻게: 항목당 {@link #TIMEOUT}, 동시에 {@link #PARALLEL} 개까지. 모르면(시간 초과 · 예외) 회색으로 하지 않고, 다음 확인은
 *       창 활성화 때 {@link #STALE_AFTER} 뒤다.
 * </ul>
 *
 * <p>확인 시각 · 진행 중 집합은 {@link #monitor} 로 지킨다 — 이벤트 콜백(actor 스레드) · 창 활성화(EDT) · 확인(I/O 스레드)이 함께
 * 만진다. 출력 포트 일정을 조율하는 가변 상태라 규칙 8 의 예외로 application 에 둔다(ADR-0013 과 같은 근거).
 */
public final class MissingPathScanner implements CheckMissingPathsUseCase {
    /** 항목 하나의 확인 시간 제한. */
    static final Duration TIMEOUT = Duration.ofMillis(500);
    /** 창 활성화 때 다시 확인할 만큼 지난 시간. */
    static final Duration STALE_AFTER = Duration.ofMinutes(5);
    /** 동시에 확인하는 항목 수. */
    static final int PARALLEL = 4;

    private static final System.Logger LOG = System.getLogger(MissingPathScanner.class.getName());

    private final StateActor state;
    private final DirectoryProbe probe;
    private final Executor io;
    private final InstantSource clock;
    private final Semaphore slots = new Semaphore(PARALLEL);
    private final Object monitor = new Object();

    // 아래는 monitor 로 지킨다
    private final Map<WorkspacePath, Instant> checkedAt = new HashMap<>();
    private final Set<WorkspacePath> inFlight = new HashSet<>();

    /**
     * 만들면서 {@code state} 의 이벤트를 구독한다. 이력을 불러오는 명령보다 먼저 만들어야 시작 뒤 한 번의 확인을 놓치지 않는다.
     *
     * @param io 확인 하나를 돌릴 실행기. 블로킹이라 운영은 가상 스레드
     */
    public MissingPathScanner(StateActor state, DirectoryProbe probe, Executor io, InstantSource clock) {
        this.state = Objects.requireNonNull(state, "state");
        this.probe = Objects.requireNonNull(probe, "probe");
        this.io = Objects.requireNonNull(io, "io");
        this.clock = Objects.requireNonNull(clock, "clock");
        state.subscribe(this::onEvent);
    }

    @Override
    public void windowActivated() {
        scan(Optional.of(STALE_AFTER));
    }

    // actor 스레드. 콜백 시점의 snapshot 이 이 이벤트를 낸 전이의 결과다
    private void onEvent(AppEvent event) {
        if (event instanceof AppEvent.HistoryChanged || event instanceof AppEvent.SessionsChanged) {
            scan(Optional.empty());
        }
    }

    // 확인한 적 없는 항목, staleAfter 가 있으면 그만큼 지난 항목도 맡긴다
    private void scan(Optional<Duration> staleAfter) {
        AppState current = state.snapshot();
        Set<ShellEnvironment> awake =
                BackgroundChecks.awakeDistros(current.sessions().leaves());
        Instant now = clock.instant();
        List<WorkspacePath> due = new ArrayList<>();
        synchronized (monitor) {
            checkedAt.keySet().retainAll(current.history().entries().keySet());
            for (WorkspacePath path : current.history().entries().keySet()) {
                if (watched(path, awake)
                        && !inFlight.contains(path)
                        && BackgroundChecks.due(Optional.ofNullable(checkedAt.get(path)), staleAfter, now)) {
                    inFlight.add(path);
                    due.add(path);
                }
            }
        }
        for (WorkspacePath path : due) {
            io.execute(() -> check(path));
        }
    }

    // Windows 경로는 늘, WSL 경로는 그 배포판이 깨어 있을 때만
    private static boolean watched(WorkspacePath path, Set<ShellEnvironment> awake) {
        return switch (path.env()) {
            case ShellEnvironment.Windows _ -> true;
            case ShellEnvironment.Wsl wsl -> awake.contains(wsl);
        };
    }

    // I/O 스레드. 슬롯이 빌 때까지 기다린다(가상 스레드라 싸다)
    private void check(WorkspacePath path) {
        Optional<Boolean> answer;
        slots.acquireUninterruptibly();
        try {
            answer = probe.isDirectory(path, TIMEOUT);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "경로 확인기가 예외를 던졌다 — 모름으로 본다", e);
            answer = Optional.empty();
        } finally {
            slots.release();
        }
        synchronized (monitor) {
            inFlight.remove(path);
            checkedAt.put(path, clock.instant());
        }
        // 결과가 지금 표시와 같으면 명령을 내지 않는다 — 시작 때 있는 경로마다 빈 명령이 쌓이지 않게
        if (answer.isPresent()) {
            boolean exists = answer.get();
            boolean shownMissing = state.snapshot().missing().contains(path);
            if (exists == shownMissing) {
                state.submit(new MissingPathChecked(path, exists));
            }
        }
    }
}
