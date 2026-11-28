package tanager.application.service;

import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import tanager.application.port.in.AppEvent;
import tanager.application.port.in.ImportConversationsUseCase;
import tanager.application.port.out.AppConfig;
import tanager.application.port.out.ConversationError;
import tanager.application.port.out.ConversationScan;
import tanager.application.port.out.ConversationSource;
import tanager.application.port.out.HomeError;
import tanager.application.port.out.HostFiles;
import tanager.application.state.AppState;
import tanager.application.state.ConversationsCleared;
import tanager.application.state.ConversationsScanned;
import tanager.application.state.ConversationsUnavailable;
import tanager.application.state.HistoryStore;
import tanager.application.state.StateActor;
import tanager.domain.history.ImportOrigin;
import tanager.domain.history.SessionLeaf;
import tanager.domain.history.SessionStatus;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.VisitSource;
import tanager.domain.history.WorkspacePath;
import tanager.domain.shared.Result;

/**
 * AI 도구의 이전 대화를 백그라운드로 읽어 이력 · 상태에 넣는다(FR-TK-09 · FR-MON-02, #215, ADR-0020).
 *
 * <ul>
 *   <li>언제: 설정을 읽고 이력을 불러온 뒤에만. Windows 는 셸 탐지가 끝나면(환경 순서에 있으면) 한 번, WSL 배포판은 그 배포판에 실행 중
 *       세션이 생기면 한 번 — {@code \\wsl.localhost} 를 읽으면 VM 이 깨기 때문이다(MissingPathScanner 와 같은 원칙). 창 활성화
 *       ({@link #refresh}) 때는 마지막 읽기에서 {@link #STALE_AFTER} 가 지난 origin 을 다시 읽는다(WSL 은 여전히 세션이 있을 때만).
 *   <li>어떻게: 홈({@link HostFiles#home}) → 호스트 경로 → {@link ConversationSource#scan} → {@link ConversationsScanned}. 같은
 *       origin 의 읽기는 한 번에 하나다. 홈을 모르면 읽지 않고 다음 창 활성화(60초 뒤)에 다시 해 본다.
 *   <li>끄기: 설정({@code toolkit.importClaudeProjects} · {@code toolkit.providers})이 그 출처를 끄면 읽지 않고 목록을 지운다
 *       ({@link ConversationsCleared}). 다시 켜면 그 설정 변경이 계기가 되어 읽는다.
 *   <li>실패: 형식을 모르거나 읽을 수 없으면 그 origin 을 이번 실행 동안 끄고 한 번 알린다({@link ConversationsUnavailable}). 기록
 *       폴더가 없으면 조용히 빈 목록이다.
 * </ul>
 *
 * <p>읽은 시각 · 진행 중 · 꺼진 origin 은 {@link #monitor} 로 지킨다 — 이벤트 콜백(actor 스레드) · 창 활성화(EDT) · 읽기(I/O 스레드)가
 * 함께 만진다. 출력 포트 일정을 조율하는 가변 상태라 규칙 8 의 예외로 application 에 둔다(ADR-0013 과 같은 근거).
 */
public final class ConversationImporter implements ImportConversationsUseCase {
    /** 창 활성화 때 다시 읽을 만큼 지난 시간. */
    static final Duration STALE_AFTER = Duration.ofSeconds(60);

    private static final System.Logger LOG = System.getLogger(ConversationImporter.class.getName());

    private final StateActor state;
    private final List<ConversationSource> sources;
    private final HostFiles hostFiles;
    private final Executor io;
    private final InstantSource clock;
    private final Object monitor = new Object();

    // 아래는 monitor 로 지킨다
    private final Map<ImportOrigin, Instant> readAt = new HashMap<>();
    private final Set<ImportOrigin> inFlight = new HashSet<>();
    private final Set<ImportOrigin> failed = new HashSet<>();

    /**
     * 만들면서 {@code state} 의 이벤트를 구독한다. 셸 탐지 · 설정 · 이력 불러오기보다 먼저 만들어야 첫 계기를 놓치지 않는다.
     *
     * @param sources 찾은 공급자(등록 순). 같은 출처가 또 나오면 앞의 것만 쓴다
     * @param io 읽기 하나를 돌릴 실행기. 블로킹이라 운영은 가상 스레드
     */
    public ConversationImporter(
            StateActor state, List<ConversationSource> sources, HostFiles hostFiles, Executor io, InstantSource clock) {
        this.state = Objects.requireNonNull(state, "state");
        this.sources = distinct(sources);
        this.hostFiles = Objects.requireNonNull(hostFiles, "hostFiles");
        this.io = Objects.requireNonNull(io, "io");
        this.clock = Objects.requireNonNull(clock, "clock");
        state.subscribe(this::onEvent);
    }

    private static List<ConversationSource> distinct(List<ConversationSource> sources) {
        Map<VisitSource, ConversationSource> bySource = new LinkedHashMap<>();
        for (ConversationSource source : sources) {
            if (bySource.putIfAbsent(source.source(), source) != null) {
                LOG.log(
                        Level.WARNING,
                        "같은 출처의 대화 공급자가 또 있어 뺀다: {0}",
                        source.source().id());
            }
        }
        return List.copyOf(bySource.values());
    }

    @Override
    public void refresh() {
        scan(Optional.of(STALE_AFTER));
    }

    // actor 스레드. 콜백 시점의 snapshot 이 이 이벤트를 낸 전이의 결과다
    private void onEvent(AppEvent event) {
        if (event instanceof AppEvent.SettingsChanged(AppConfig config)) {
            clearTurnedOff(config);
            scan(Optional.empty());
        } else if (event instanceof AppEvent.EnvironmentsChanged
                || event instanceof AppEvent.SessionsChanged
                // 이력 불러오기(시작 뒤 한 번)를 기다린 읽기를 놓치지 않게. 읽은 origin 은 건너뛰므로 싸다
                || event instanceof AppEvent.HistoryChanged) {
            scan(Optional.empty());
        }
    }

    // 꺼진 출처의 목록을 지우고 읽은 시각을 잊는다 — 다시 켜면 그 설정 변경에 바로 읽게
    private void clearTurnedOff(AppConfig config) {
        synchronized (monitor) {
            readAt.keySet().removeIf(origin -> !config.toolkit().importsConversations(origin.source()));
        }
        for (ImportOrigin origin : state.snapshot().conversations().keySet()) {
            if (!config.toolkit().importsConversations(origin.source())) {
                state.submit(new ConversationsCleared(Optional.of(origin)));
            }
        }
    }

    // 읽은 적 없는 origin, staleAfter 가 있으면 그만큼 지난 origin 도 맡긴다
    private void scan(Optional<Duration> staleAfter) {
        AppState current = state.snapshot();
        if (current.settings().isEmpty() || current.store() instanceof HistoryStore.Loading) {
            return;
        }
        AppConfig config = current.settings().get();
        List<ShellEnvironment> watched = watched(current);
        Instant now = clock.instant();
        List<Read> due = new ArrayList<>();
        synchronized (monitor) {
            for (ConversationSource source : sources) {
                if (!config.toolkit().importsConversations(source.source())) {
                    continue;
                }
                for (ShellEnvironment env : watched) {
                    ImportOrigin origin = new ImportOrigin(source.source(), env);
                    if (source.supports(env)
                            && !failed.contains(origin)
                            && !inFlight.contains(origin)
                            && due(readAt.get(origin), staleAfter, now)) {
                        inFlight.add(origin);
                        readAt.put(origin, now);
                        due.add(new Read(source, origin, now));
                    }
                }
            }
        }
        for (Read read : due) {
            io.execute(() -> read(read));
        }
    }

    // 탐지된 Windows 환경은 늘, WSL 배포판은 실행 중 세션이 있을 때만(VM 을 깨우지 않는다)
    private static List<ShellEnvironment> watched(AppState current) {
        List<ShellEnvironment> envs = new ArrayList<>();
        for (ShellEnvironment env : current.environments()) {
            if (env instanceof ShellEnvironment.Windows) {
                envs.add(env);
            }
        }
        for (SessionLeaf s : current.sessions().leaves()) {
            if (s.env() instanceof ShellEnvironment.Wsl
                    && s.status() instanceof SessionStatus.Running
                    && !envs.contains(s.env())) {
                envs.add(s.env());
            }
        }
        return envs;
    }

    private static boolean due(Instant read, Optional<Duration> staleAfter, Instant now) {
        return read == null || staleAfter.map(d -> !read.plus(d).isAfter(now)).orElse(false);
    }

    private record Read(ConversationSource source, ImportOrigin origin, Instant at) {}

    // I/O 스레드
    private void read(Read read) {
        try {
            Optional<Path> home = home(read.origin().env());
            if (home.isEmpty()) {
                return;
            }
            Result<ConversationScan, ConversationError> result;
            try {
                result = read.source().scan(read.origin().env(), home.get());
            } catch (RuntimeException e) {
                LOG.log(
                        Level.ERROR,
                        "대화 공급자가 예외를 던졌다 — 읽을 수 없음으로 본다: {0}",
                        e.getClass().getName());
                result = Result.err(new ConversationError.Unreadable());
            }
            switch (result) {
                case Result.Ok<ConversationScan, ConversationError>(ConversationScan scan) ->
                    state.submit(new ConversationsScanned(read.origin(), read.at(), scan));
                case Result.Err<ConversationScan, ConversationError>(ConversationError error) -> failed(read, error);
            }
        } finally {
            synchronized (monitor) {
                inFlight.remove(read.origin());
            }
        }
    }

    // 홈(그 환경의 경로) → 이 앱이 열 경로. 모르면 empty — 읽은 시각은 남아 창 활성화 때 다시 해 본다
    private Optional<Path> home(ShellEnvironment env) {
        Result<WorkspacePath, HomeError> home;
        try {
            home = hostFiles.home(env);
        } catch (RuntimeException e) {
            LOG.log(
                    Level.WARNING,
                    "홈을 묻다 예외가 났다 — 다음에 다시 해 본다: {0}",
                    e.getClass().getName());
            return Optional.empty();
        }
        Optional<Path> host = home.value().flatMap(hostFiles::hostPath);
        if (host.isEmpty()) {
            LOG.log(Level.INFO, "{0} 의 홈을 몰라 대화를 읽지 않는다 — 다음에 다시 해 본다", env);
        }
        return host;
    }

    private void failed(Read read, ConversationError error) {
        ImportOrigin origin = read.origin();
        switch (error) {
            case ConversationError.NoHistory() ->
                state.submit(new ConversationsScanned(origin, read.at(), new ConversationScan(List.of(), 0)));
            case ConversationError.UnknownFormat() -> disable(origin, new AppEvent.ImportProblem.UnknownFormat(origin));
            case ConversationError.Unreadable() -> disable(origin, new AppEvent.ImportProblem.Unreadable(origin));
        }
    }

    private void disable(ImportOrigin origin, AppEvent.ImportProblem problem) {
        LOG.log(Level.WARNING, "이번 실행 동안 대화를 가져오지 않는다: {0}", problem.describe());
        synchronized (monitor) {
            failed.add(origin);
        }
        state.submit(new ConversationsUnavailable(problem));
    }
}
