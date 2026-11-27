package tanager.application.port.out;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import tanager.domain.history.WorkspacePath;
import tanager.domain.monitor.DiskUsage;
import tanager.domain.monitor.HostUsage;
import tanager.domain.monitor.Usage;
import tanager.domain.monitor.WslUsage;
import tanager.domain.shared.Result;

/**
 * {@link ResourceProbe} 의 수작업 Fake. 돌려줄 값을 정해 두고 호출을 기록한다. 기본값: CPU 10% · 4코어 · 메모리 절반, 디스크는 경로의 루트로
 * 절반, WSL 은 절반 · 절반. 스레드 안전.
 */
public final class FakeResourceProbe implements ResourceProbe {
    /** 기본 메모리 · 디스크 사용량(절반). */
    public static final Usage HALF = new Usage(50, 100);

    private HostUsage host = new HostUsage(0.10, 4, HALF);
    private Function<WorkspacePath, Optional<DiskUsage>> disk = cwd -> Optional.of(new DiskUsage(cwd.root(), HALF));
    private Function<String, Result<WslUsage, ProbeError>> wsl = distro -> Result.ok(new WslUsage(distro, HALF, HALF));
    private Optional<RuntimeException> hostFailure = Optional.empty();
    private int hostCalls;
    private final List<WorkspacePath> diskCalls = new ArrayList<>();
    private final List<String> wslCalls = new ArrayList<>();
    private final List<Duration> wslTimeouts = new ArrayList<>();

    /** {@code host()} 가 돌려줄 값. */
    public synchronized FakeResourceProbe withHost(HostUsage next) {
        this.host = Objects.requireNonNull(next, "next");
        return this;
    }

    /** {@code host()} 가 이 예외를 던진다(계약 위반 흉내 — 호출 수는 센다). */
    public synchronized FakeResourceProbe failHostWith(RuntimeException failure) {
        this.hostFailure = Optional.of(failure);
        return this;
    }

    /** {@code wsl()} 이 돌려줄 값. */
    public synchronized FakeResourceProbe withWsl(Result<WslUsage, ProbeError> next) {
        Objects.requireNonNull(next, "next");
        this.wsl = _ -> next;
        return this;
    }

    @Override
    public synchronized HostUsage host() {
        hostCalls++;
        if (hostFailure.isPresent()) {
            throw hostFailure.get();
        }
        return host;
    }

    @Override
    public synchronized Optional<DiskUsage> disk(WorkspacePath cwd) {
        diskCalls.add(cwd);
        return disk.apply(cwd);
    }

    @Override
    public synchronized Result<WslUsage, ProbeError> wsl(String distro, Duration timeout) {
        wslCalls.add(distro);
        wslTimeouts.add(timeout);
        return wsl.apply(distro);
    }

    public synchronized int hostCalls() {
        return hostCalls;
    }

    /** {@code disk()} 에 넘어온 경로들(순서대로). */
    public synchronized List<WorkspacePath> diskCalls() {
        return List.copyOf(diskCalls);
    }

    /** {@code wsl()} 에 넘어온 배포판들(순서대로). */
    public synchronized List<String> wslCalls() {
        return List.copyOf(wslCalls);
    }

    /** {@code wsl()} 에 넘어온 시간 초과들(순서대로). */
    public synchronized List<Duration> wslTimeouts() {
        return List.copyOf(wslTimeouts);
    }

    /** 모든 수집 호출 수. */
    public synchronized int totalCalls() {
        return hostCalls + diskCalls.size() + wslCalls.size();
    }
}
