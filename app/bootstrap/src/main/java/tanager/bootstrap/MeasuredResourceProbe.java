package tanager.bootstrap;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import tanager.application.port.out.ProbeError;
import tanager.application.port.out.ResourceProbe;
import tanager.domain.history.WorkspacePath;
import tanager.domain.monitor.DiskUsage;
import tanager.domain.monitor.HostUsage;
import tanager.domain.monitor.WslUsage;
import tanager.domain.shared.Result;

/** 측정 모드(#222)의 수집기 — {@code wsl.exe} 를 부르기 직전에 알리고 나머지는 그대로 넘긴다(NFR-10 의 호출 간격). */
final class MeasuredResourceProbe implements ResourceProbe {
    private final ResourceProbe delegate;
    private final Runnable wslCalled;

    MeasuredResourceProbe(ResourceProbe delegate, Runnable wslCalled) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.wslCalled = Objects.requireNonNull(wslCalled, "wslCalled");
    }

    @Override
    public HostUsage host() {
        return delegate.host();
    }

    @Override
    public Optional<DiskUsage> disk(WorkspacePath cwd) {
        return delegate.disk(cwd);
    }

    @Override
    public Result<WslUsage, ProbeError> wsl(String distro, Duration timeout) {
        wslCalled.run();
        return delegate.wsl(distro, timeout);
    }
}
