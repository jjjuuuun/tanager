package tanager.application.service;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import tanager.application.port.in.QueryToolkitUseCase;
import tanager.application.port.out.Cancellable;
import tanager.application.port.out.HomeError;
import tanager.application.port.out.HostFiles;
import tanager.application.port.out.ScanStamp;
import tanager.application.port.out.Scheduler;
import tanager.application.port.out.ToolkitConfig;
import tanager.application.port.out.ToolkitError;
import tanager.application.port.out.ToolkitProvider;
import tanager.application.port.out.ToolkitQuery;
import tanager.application.port.out.ToolkitScan;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.shared.Result;
import tanager.domain.toolkit.ProviderId;
import tanager.domain.toolkit.SourceRef;
import tanager.domain.toolkit.ToolkitItem;
import tanager.domain.toolkit.ToolkitView;
import tanager.domain.toolkit.ToolkitWarning;

/**
 * 공급자들을 불러 한 경로의 도구함을 만든다(FR-TK-01 · FR-TK-08 · NFR-07, ADR-0019). 홈 묻기와 공급자마다의 읽기는 I/O 실행기에서 돌고,
 * 공급자마다의 시간 제한은 {@link Scheduler} 로 건다. 도구함은 저장 상태가 아니라 {@code StateActor} 를 거치지 않는다.
 *
 * <p>캐시와 진행 중 조회는 출력 포트 조율용 가변 상태다 — {@code lock} 하나로 지킨다(java-style 8, ADR-0013).
 */
public final class ToolkitQueryService implements QueryToolkitUseCase {
    private static final System.Logger LOG = System.getLogger(ToolkitQueryService.class.getName());

    /** 공급자 하나의 시간 제한. 넘기면 그 공급자만 {@code ProviderFailed}. */
    static final Duration PROVIDER_TIMEOUT = Duration.ofSeconds(10);

    /** 캐시에 둘 경로 수(LRU). */
    static final int CACHED_PATHS = 64;

    private final List<ToolkitProvider> providers;
    private final HostFiles hostFiles;
    private final Supplier<ToolkitConfig> config;
    private final Executor io;
    private final Scheduler timer;
    private final Object lock = new Object();
    private final Map<WorkspacePath, Map<ProviderId, ToolkitScan>> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<WorkspacePath, Map<ProviderId, ToolkitScan>> eldest) {
            return size() > CACHED_PATHS;
        }
    };
    private final Map<WorkspacePath, CompletableFuture<ToolkitView>> inFlight = new HashMap<>();

    /**
     * @param providers 찾은 공급자(등록 순). 같은 id 가 또 나오면 앞의 것만 쓴다
     * @param config 지금 설정의 {@code toolkit} — 조회 때마다 읽는다
     * @param io 홈 묻기 · 공급자 읽기를 돌릴 실행기(블로킹 I/O — 가상 스레드)
     * @param timer 공급자 시간 제한
     */
    public ToolkitQueryService(
            List<ToolkitProvider> providers,
            HostFiles hostFiles,
            Supplier<ToolkitConfig> config,
            Executor io,
            Scheduler timer) {
        this.providers = distinct(providers);
        this.hostFiles = Objects.requireNonNull(hostFiles, "hostFiles");
        this.config = Objects.requireNonNull(config, "config");
        this.io = Objects.requireNonNull(io, "io");
        this.timer = Objects.requireNonNull(timer, "timer");
    }

    private static List<ToolkitProvider> distinct(List<ToolkitProvider> providers) {
        Set<ProviderId> seen = new HashSet<>();
        List<ToolkitProvider> kept = new ArrayList<>();
        for (ToolkitProvider provider : providers) {
            if (seen.add(provider.id())) {
                kept.add(provider);
            } else {
                LOG.log(
                        System.Logger.Level.WARNING,
                        "같은 id 의 도구함 공급자가 또 있어 뺀다: {0}",
                        provider.id().value());
            }
        }
        return List.copyOf(kept);
    }

    @Override
    public CompletionStage<ToolkitView> query(WorkspacePath path, Freshness freshness) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(freshness, "freshness");
        CompletableFuture<ToolkitView> view;
        synchronized (lock) {
            CompletableFuture<ToolkitView> running = inFlight.get(path);
            if (running != null) {
                return running.copy();
            }
            view = new CompletableFuture<>();
            inFlight.put(path, view);
        }
        view.whenComplete((_, _) -> {
            synchronized (lock) {
                inFlight.remove(path, view);
            }
        });
        start(path, freshness, view);
        return view.copy();
    }

    @Override
    public List<ProviderId> providers(ShellEnvironment env) {
        List<ProviderId> ids = new ArrayList<>();
        for (ToolkitProvider provider : chosen(env)) {
            ids.add(provider.id());
        }
        return List.copyOf(ids);
    }

    @Override
    public Optional<Path> hostFile(SourceRef source) {
        return hostFiles.hostPath(Objects.requireNonNull(source, "source").file());
    }

    // 설정 toolkit.providers(비면 모두) 중 그 환경을 지원하는 공급자, 등록 순
    private List<ToolkitProvider> chosen(ShellEnvironment env) {
        Set<String> wanted = Set.copyOf(config.get().providers());
        List<ToolkitProvider> chosen = new ArrayList<>();
        for (ToolkitProvider provider : providers) {
            boolean configured =
                    wanted.isEmpty() || wanted.contains(provider.id().value());
            if (configured && provider.supports(env)) {
                chosen.add(provider);
            }
        }
        return chosen;
    }

    // I/O 실행기에서: 홈을 묻고 공급자들을 나란히 부른 뒤 모두 끝나거나 시간을 넘기면 합친다
    private void collect(WorkspacePath path, Freshness freshness, CompletableFuture<ToolkitView> view) {
        List<ToolkitProvider> chosen = chosen(path.env());
        if (chosen.isEmpty()) {
            // 부를 공급자가 없으면 홈도 묻지 않는다 — WSL VM 을 깨우지 않는다
            view.complete(new ToolkitView(path, List.of(), List.of()));
            return;
        }
        List<ToolkitWarning> warnings = new ArrayList<>();
        Optional<WorkspacePath> home = home(path.env(), warnings);
        ToolkitQuery query = new ToolkitQuery(path, home, hostFiles::hostPath);
        List<CompletableFuture<Optional<ToolkitScan>>> outcomes = new ArrayList<>();
        for (ToolkitProvider provider : chosen) {
            outcomes.add(scanWithin(provider, query, freshness));
        }
        CompletableFuture.allOf(outcomes.toArray(CompletableFuture[]::new))
                .thenRun(() -> view.complete(merge(path, warnings, chosen, outcomes)));
    }

    private Optional<WorkspacePath> home(ShellEnvironment env, List<ToolkitWarning> warnings) {
        return switch (hostFiles.home(env)) {
            case Result.Ok<WorkspacePath, HomeError>(WorkspacePath home) -> Optional.of(home);
            case Result.Err<WorkspacePath, HomeError>(HomeError error) -> {
                LOG.log(System.Logger.Level.INFO, "홈을 몰라 사용자 범위 없이 도구함을 읽는다: {0}", error);
                warnings.add(new ToolkitWarning.HomeUnknown(env));
                yield Optional.empty();
            }
        };
    }

    // 공급자 하나의 결과. empty = 실패(err · 예외 · 시간 초과)
    private CompletableFuture<Optional<ToolkitScan>> scanWithin(
            ToolkitProvider provider, ToolkitQuery query, Freshness freshness) {
        CompletableFuture<Optional<ToolkitScan>> outcome = new CompletableFuture<>();
        Cancellable deadline = timer.schedule(PROVIDER_TIMEOUT, () -> {
            if (outcome.complete(Optional.empty())) {
                LOG.log(
                        System.Logger.Level.WARNING,
                        "도구함 공급자 {0} 가 {1} 안에 끝나지 않아 실패로 본다",
                        provider.id().value(),
                        PROVIDER_TIMEOUT);
            }
        });
        outcome.whenComplete((_, _) -> deadline.cancel());
        try {
            io.execute(() -> outcome.complete(read(provider, query, freshness)));
        } catch (RejectedExecutionException e) {
            outcome.complete(Optional.empty());
        }
        return outcome;
    }

    private Optional<ToolkitScan> read(ToolkitProvider provider, ToolkitQuery query, Freshness freshness) {
        ProviderId id = provider.id();
        try {
            if (freshness == Freshness.REUSE_IF_UNCHANGED) {
                Optional<ToolkitScan> reused = reusable(provider, query);
                if (reused.isPresent()) {
                    return reused;
                }
            }
            return switch (provider.scan(query)) {
                case Result.Ok<ToolkitScan, ToolkitError>(ToolkitScan scan) -> {
                    ToolkitScan own = ownItemsOnly(id, scan);
                    remember(query.path(), id, own);
                    yield Optional.of(own);
                }
                case Result.Err<ToolkitScan, ToolkitError>(ToolkitError error) -> {
                    LOG.log(System.Logger.Level.WARNING, "도구함 공급자 {0} 실패: {1}", id.value(), error.describe());
                    forget(query.path(), id);
                    yield Optional.empty();
                }
            };
        } catch (RuntimeException e) {
            // 예외 메시지는 남기지 않는다 — 읽던 내용(비밀값 포함 가능)이 들어 있을 수 있다
            LOG.log(
                    System.Logger.Level.ERROR,
                    "도구함 공급자 {0} 가 예외를 던졌다(SPI 위반): {1}",
                    id.value(),
                    e.getClass().getName());
            forget(query.path(), id);
            return Optional.empty();
        }
    }

    // 지난 scan 의 지문이 지금 지문과 같으면 그 scan
    private Optional<ToolkitScan> reusable(ToolkitProvider provider, ToolkitQuery query) {
        Optional<ToolkitScan> cached;
        synchronized (lock) {
            cached = Optional.ofNullable(cache.get(query.path())).map(scans -> scans.get(provider.id()));
        }
        if (cached.isEmpty()) {
            return Optional.empty();
        }
        Optional<ScanStamp> now = provider.stamp(query);
        return now.isPresent() && now.equals(cached.get().stamp()) ? cached : Optional.empty();
    }

    private void remember(WorkspacePath path, ProviderId id, ToolkitScan scan) {
        synchronized (lock) {
            if (scan.stamp().isEmpty()) {
                forgetLocked(path, id);
                return;
            }
            cache.computeIfAbsent(path, _ -> new HashMap<>()).put(id, scan);
        }
    }

    private void forget(WorkspacePath path, ProviderId id) {
        synchronized (lock) {
            forgetLocked(path, id);
        }
    }

    private void forgetLocked(WorkspacePath path, ProviderId id) {
        Map<ProviderId, ToolkitScan> scans = cache.get(path);
        if (scans != null) {
            scans.remove(id);
            if (scans.isEmpty()) {
                cache.remove(path);
            }
        }
    }

    // 다른 공급자 id 의 항목은 버린다(SPI 오용 방지)
    private static ToolkitScan ownItemsOnly(ProviderId id, ToolkitScan scan) {
        List<ToolkitItem> own = new ArrayList<>(scan.items().size());
        for (ToolkitItem item : scan.items()) {
            if (item.provider().equals(id)) {
                own.add(item);
            }
        }
        int dropped = scan.items().size() - own.size();
        if (dropped == 0) {
            return scan;
        }
        LOG.log(System.Logger.Level.WARNING, "도구함 공급자 {0} 가 다른 공급자의 항목 {1}개를 돌려줘 뺀다", id.value(), dropped);
        return new ToolkitScan(own, scan.warnings(), scan.stamp());
    }

    private static ToolkitView merge(
            WorkspacePath path,
            List<ToolkitWarning> head,
            List<ToolkitProvider> chosen,
            List<CompletableFuture<Optional<ToolkitScan>>> outcomes) {
        List<ToolkitItem> items = new ArrayList<>();
        List<ToolkitWarning> warnings = new ArrayList<>(head);
        for (int i = 0; i < chosen.size(); i++) {
            Optional<ToolkitScan> scan = outcomes.get(i).join();
            if (scan.isPresent()) {
                items.addAll(scan.get().items());
                warnings.addAll(scan.get().warnings());
            } else {
                warnings.add(new ToolkitWarning.ProviderFailed(chosen.get(i).id()));
            }
        }
        return new ToolkitView(path, items, warnings);
    }

    // 조회 하나를 실행기에 맡긴다. 거절되거나(종료 중) 예상 밖의 예외면 그 조회를 예외로 끝낸다 — 기다리는 쪽이 멈추지 않게
    private void start(WorkspacePath path, Freshness freshness, CompletableFuture<ToolkitView> view) {
        try {
            io.execute(() -> {
                try {
                    collect(path, freshness, view);
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.ERROR, "도구함 조회 실패", e);
                    view.completeExceptionally(e);
                }
            });
        } catch (RejectedExecutionException e) {
            view.completeExceptionally(e);
        }
    }
}
