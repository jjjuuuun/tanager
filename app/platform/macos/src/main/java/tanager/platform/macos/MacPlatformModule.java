package tanager.platform.macos;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import tanager.application.platform.OperatingSystem;
import tanager.application.port.out.Integration;
import tanager.application.port.out.LaunchCommand;
import tanager.application.port.out.PathTranslation;
import tanager.application.port.out.PlatformDirs;
import tanager.application.port.out.PlatformModule;
import tanager.application.port.out.ProbeError;
import tanager.application.port.out.ResourceProbe;
import tanager.application.port.out.ShellIntegration;
import tanager.application.port.out.SystemAppearance;
import tanager.application.port.out.SystemTools;
import tanager.domain.history.WorkspacePath;
import tanager.domain.monitor.DiskUsage;
import tanager.domain.monitor.HostUsage;
import tanager.domain.monitor.Usage;
import tanager.domain.monitor.WslUsage;
import tanager.domain.session.ShellProfile;
import tanager.domain.session.WorkingDir;
import tanager.domain.shared.Result;

/** macOS (M7 에서 채운다). Java 의 os.name 은 "Mac OS X". */
public final class MacPlatformModule implements PlatformModule {

    @Override
    public String id() {
        return "macos";
    }

    @Override
    public boolean supports(OperatingSystem os) {
        return os.name().startsWith("Mac");
    }

    /** M7 에서 채운다. 그때까지 빈 목록. */
    @Override
    public List<ShellProfile> shells() {
        return List.of();
    }

    /** M7 에서 채운다. 그때까지는 지원하지 않는다. */
    @Override
    public LaunchCommand launchCommand(ShellProfile profile, WorkingDir dir, Integration integration) {
        throw new UnsupportedOperationException("macOS 셸 기동은 M7 에서 지원한다");
    }

    /** M7 에서 채운다(~/Library/Application Support/Tanager). 그때까지는 지원하지 않는다. */
    @Override
    public PlatformDirs dirs() {
        throw new UnsupportedOperationException("macOS 앱 폴더는 M7 에서 지원한다");
    }

    /** M7 에서 zsh 판을 채운다. 그때까지 셸 통합 없음. */
    @Override
    public ShellIntegration integration() {
        return () -> new Integration.Unavailable(new Integration.Problem.NotSupported());
    }

    /** macOS 셸 환경(Posix)은 M7 에서 더한다. 그때까지는 같은 환경만 그대로 돌려준다. */
    @Override
    public PathTranslation paths() {
        return (from, to) -> from.env().equals(to) ? Optional.of(from) : Optional.empty();
    }

    /** M7 에서 NSWorkspace 의 '동작 줄이기'를 읽는다. 그때까지 애니메이션을 켠다. */
    @Override
    public SystemAppearance appearance() {
        return SystemAppearance.DEFAULT;
    }

    /** M7 에서 디스크를 채운다. 그때까지 호스트 CPU · 메모리만 JDK 로 잰다. macOS 에는 WSL 이 없다. */
    @Override
    public ResourceProbe resources() {
        return new ResourceProbe() {
            @Override
            public HostUsage host() {
                var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
                double load = os.getCpuLoad();
                long total = Math.max(1, os.getTotalMemorySize());
                long free = Math.clamp(os.getFreeMemorySize(), 0, total);
                return new HostUsage(
                        load >= 0 ? Math.min(1, load) : 0,
                        Math.max(1, os.getAvailableProcessors()),
                        new Usage(total - free, total));
            }

            @Override
            public Optional<DiskUsage> disk(WorkspacePath cwd) {
                return Optional.empty();
            }

            @Override
            public Result<WslUsage, ProbeError> wsl(String distro, Duration timeout) {
                return Result.err(new ProbeError.Unsupported());
            }
        };
    }

    /** M7 에서 활성 상태 보기 · 디스크 유틸리티를 연다. 그때까지 아무것도 열지 않는다. */
    @Override
    public SystemTools systemTools() {
        return item -> {};
    }
}
