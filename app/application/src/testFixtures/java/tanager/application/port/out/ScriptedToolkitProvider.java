package tanager.application.port.out;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.shared.Result;
import tanager.domain.toolkit.ProviderId;

/**
 * {@link ToolkitProvider} 의 수작업 Fake(#209). {@link #withScan} 으로 정한 결과를 돌려주고(정하지 않으면 빈 scan), {@link #withStamp}
 * 로 정한 지문을 {@code stamp} 와 scan 결과에 같이 싣는다. 받은 쿼리와 부른 스레드를 기록한다. {@link #duringScan} 은 scan 안에서
 * 돌린다 — 오래 걸리는 공급자를 가짜 시계로 흉내 낼 때 쓴다. 스레드 안전.
 */
public final class ScriptedToolkitProvider implements ToolkitProvider {
    private final ProviderId id;
    private final Predicate<ShellEnvironment> supported;
    private final List<ToolkitQuery> scans = new ArrayList<>();
    private final List<ToolkitQuery> stamps = new ArrayList<>();
    private final List<Thread> scanThreads = new ArrayList<>();
    private Result<ToolkitScan, ToolkitError> result =
            Result.ok(new ToolkitScan(List.of(), List.of(), Optional.empty()));
    private Optional<ScanStamp> stamp = Optional.empty();
    private Runnable duringScan = () -> {};
    private Optional<RuntimeException> failure = Optional.empty();

    public ScriptedToolkitProvider(String id, Predicate<ShellEnvironment> supported) {
        this.id = new ProviderId(Objects.requireNonNull(id, "id"));
        this.supported = Objects.requireNonNull(supported, "supported");
    }

    /** 모든 환경을 지원한다. */
    public static ScriptedToolkitProvider everywhere(String id) {
        return new ScriptedToolkitProvider(id, _ -> true);
    }

    /** Windows 환경만 지원한다. */
    public static ScriptedToolkitProvider windowsOnly(String id) {
        return new ScriptedToolkitProvider(id, env -> env instanceof ShellEnvironment.Windows);
    }

    /** scan 이 돌려줄 결과. ok 면 지금 정한 지문이 scan 에 실린다. */
    public synchronized ScriptedToolkitProvider withScan(Result<ToolkitScan, ToolkitError> next) {
        this.result = Objects.requireNonNull(next, "next");
        return this;
    }

    /** {@code stamp} 와 다음 scan 결과에 실을 지문. */
    public synchronized ScriptedToolkitProvider withStamp(Optional<ScanStamp> next) {
        this.stamp = Objects.requireNonNull(next, "next");
        return this;
    }

    /** scan 안에서 결과를 돌려주기 전에 돌릴 일. */
    public synchronized ScriptedToolkitProvider duringScan(Runnable task) {
        this.duringScan = Objects.requireNonNull(task, "task");
        return this;
    }

    /** scan 이 이 예외를 던진다(SPI 를 어긴 공급자 흉내). */
    public synchronized ScriptedToolkitProvider throwing(RuntimeException e) {
        this.failure = Optional.of(Objects.requireNonNull(e, "e"));
        return this;
    }

    @Override
    public ProviderId id() {
        return id;
    }

    @Override
    public boolean supports(ShellEnvironment env) {
        return supported.test(env);
    }

    @Override
    public Result<ToolkitScan, ToolkitError> scan(ToolkitQuery query) {
        Runnable hook;
        synchronized (this) {
            scans.add(query);
            scanThreads.add(Thread.currentThread());
            hook = duringScan;
        }
        hook.run();
        synchronized (this) {
            if (failure.isPresent()) {
                throw failure.get();
            }
            Optional<ScanStamp> current = stamp;
            return result.map(scan -> new ToolkitScan(scan.items(), scan.warnings(), current));
        }
    }

    @Override
    public synchronized Optional<ScanStamp> stamp(ToolkitQuery query) {
        stamps.add(query);
        return stamp;
    }

    /** scan 이 받은 쿼리(받은 순서, 불변 사본). */
    public synchronized List<ToolkitQuery> scans() {
        return List.copyOf(scans);
    }

    /** stamp 가 받은 쿼리(받은 순서, 불변 사본). */
    public synchronized List<ToolkitQuery> stamps() {
        return List.copyOf(stamps);
    }

    /** scan 을 부른 스레드(부른 순서, 불변 사본). */
    public synchronized List<Thread> scanThreads() {
        return List.copyOf(scanThreads);
    }
}
