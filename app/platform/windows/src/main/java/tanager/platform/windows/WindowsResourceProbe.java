package tanager.platform.windows;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import tanager.application.port.out.PathTranslation;
import tanager.application.port.out.ProbeError;
import tanager.application.port.out.ResourceProbe;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.monitor.DiskUsage;
import tanager.domain.monitor.HostUsage;
import tanager.domain.monitor.Usage;
import tanager.domain.monitor.WslUsage;
import tanager.domain.shared.Result;

/**
 * Windows 의 리소스 수집기(FR-MON-01 · 02, SDD §6.6). 호스트는 JDK {@code OperatingSystemMXBean}, 디스크는 {@link FileStore}, WSL 은
 * {@code wsl.exe} 로 배포판 안의 {@code /proc/meminfo} · {@code df -kP /} 를 한 번에 읽는다.
 */
final class WindowsResourceProbe implements ResourceProbe {
    // 로그인 셸(zsh · fish …)을 거치지 않게 --exec. df 머리줄 · 숫자 형식이 로캘을 타지 않게 LC_ALL=C
    static final String WSL_SCRIPT = "cat /proc/meminfo; LC_ALL=C df -kP /";
    // wsl.exe 자체 메시지(배포판 없음 등)를 UTF-16LE 대신 UTF-8 로(CLAUDE.md 함정)
    private static final Map<String, String> WSL_ENV = Map.of("WSL_UTF8", "1");
    private static final Pattern DRIVE_ROOT = Pattern.compile("[A-Z]:\\\\");
    private static final System.Logger LOG = System.getLogger(WindowsResourceProbe.class.getName());

    /** 드라이브 루트({@code C:\}) 하나의 사용량. 잴 수 없으면 비어 있다. */
    @FunctionalInterface
    interface DiskReader {
        Optional<Usage> read(String root);
    }

    private final Supplier<HostUsage> host;
    private final DiskReader disks;
    private final CommandRunner runner;
    private final PathTranslation paths;

    WindowsResourceProbe(Supplier<HostUsage> host, DiskReader disks, CommandRunner runner, PathTranslation paths) {
        this.host = Objects.requireNonNull(host, "host");
        this.disks = Objects.requireNonNull(disks, "disks");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.paths = Objects.requireNonNull(paths, "paths");
    }

    /** JDK · FileStore · 실제 프로세스로 잰다. */
    static WindowsResourceProbe system(CommandRunner runner, PathTranslation paths) {
        return new WindowsResourceProbe(WindowsResourceProbe::jdkHost, WindowsResourceProbe::fileStore, runner, paths);
    }

    @Override
    public HostUsage host() {
        return host.get();
    }

    /** 드라이브 문자 경로만 잰다. UNC(네트워크 공유)는 응답이 없으면 수집 주기를 붙잡을 수 있어 뺀다. */
    @Override
    public Optional<DiskUsage> disk(WorkspacePath cwd) {
        return paths.translate(cwd, new ShellEnvironment.Windows())
                .map(WorkspacePath::root)
                .filter(root -> DRIVE_ROOT.matcher(root).matches())
                .flatMap(root -> disks.read(root).map(usage -> new DiskUsage(root, usage)));
    }

    @Override
    public Result<WslUsage, ProbeError> wsl(String distro, Duration timeout) {
        Objects.requireNonNull(distro, "distro");
        Objects.requireNonNull(timeout, "timeout");
        // 탐지한 배포판 이름이지만 명령줄에 넣으므로 한 번 더 막는다(#107 과 같은 이유)
        if (distro.isEmpty() || distro.chars().anyMatch(c -> c <= ' ' || c == '"' || c == 0x7f)) {
            return Result.err(new ProbeError.Failed("배포판 이름을 명령줄에 넣을 수 없다"));
        }
        List<String> command = List.of("wsl.exe", "-d", distro, "--exec", "sh", "-c", WSL_SCRIPT);
        return switch (runner.run(command, WSL_ENV, timeout)) {
            case CommandOutcome.Exited(int code, byte[] stdout)
            when code == 0 -> parse(distro, new String(stdout, StandardCharsets.UTF_8));
            case CommandOutcome.Exited(int code, byte[] _) -> Result.err(new ProbeError.Failed("종료 코드 " + code));
            case CommandOutcome.NotStarted(String reason) -> Result.err(new ProbeError.Failed(reason));
            case CommandOutcome.TimedOut(Duration after) -> Result.err(new ProbeError.TimedOut(after));
        };
    }

    private static Result<WslUsage, ProbeError> parse(String distro, String output) {
        return MeminfoParser.parse(output)
                .flatMap(memory -> DfParser.parse(output).map(disk -> new WslUsage(distro, memory, disk)));
    }

    // 사용 가능 메모리(getFreeMemorySize)는 Windows 에서 작업 관리자의 '사용 가능'과 같은 값이다 ✱
    static HostUsage jdkHost() {
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        double load = os.getCpuLoad();
        // 처음 부르거나 읽지 못하면 음수다
        double cpu = load >= 0 ? Math.min(1, load) : 0;
        long total = Math.max(1, os.getTotalMemorySize());
        long free = Math.clamp(os.getFreeMemorySize(), 0, total);
        return new HostUsage(cpu, Math.max(1, os.getAvailableProcessors()), new Usage(total - free, total));
    }

    static Optional<Usage> fileStore(String root) {
        try {
            FileStore store = Files.getFileStore(Path.of(root));
            long total = store.getTotalSpace();
            if (total <= 0) {
                return Optional.empty();
            }
            long usable = Math.clamp(store.getUsableSpace(), 0, total);
            return Optional.of(new Usage(total - usable, total));
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "디스크를 재지 못했다: " + root, e);
            return Optional.empty();
        }
    }
}
