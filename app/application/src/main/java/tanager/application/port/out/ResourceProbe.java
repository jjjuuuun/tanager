package tanager.application.port.out;

import java.time.Duration;
import java.util.Optional;
import tanager.domain.history.WorkspacePath;
import tanager.domain.monitor.DiskUsage;
import tanager.domain.monitor.HostUsage;
import tanager.domain.monitor.WslUsage;
import tanager.domain.shared.Result;

/**
 * 리소스 수집기({@link PlatformModule#resources()}, FR-MON-01 · 02). EDT 에서 부르지 않는다 — 수집기는 I/O 가상 스레드에서 부른다(SDD
 * §6.6). 예외를 던지지 않는 것이 계약이지만 부르는 쪽은 던져도 버틴다.
 */
public interface ResourceProbe {

    /** 호스트 CPU 사용률 · 코어 수 · 메모리. 외부 명령을 실행하지 않고 막지 않는다. */
    HostUsage host();

    /**
     * {@code cwd} 가 있는 드라이브의 디스크. WSL 의 {@code /mnt/<d>/…} 는 Windows 드라이브 {@code <D>:\} 로 바꿔 잰다({@link
     * PlatformModule#paths()} 와 같은 규칙). 그 밖의 WSL 경로(리눅스 쪽)와 잴 수 없는 경로는 empty — 배포판 {@code /} 는 {@link #wsl} 이
     * 잰다.
     */
    Optional<DiskUsage> disk(WorkspacePath cwd);

    /**
     * 배포판 {@code distro} 안에서 VM 메모리와 {@code /} 디스크를 잰다({@code /proc/meminfo} · {@code df -kP /}). 외부 명령을 실행하므로
     * 최대 {@code timeout} 막힌다 — 넘으면 프로세스를 끝내고 {@link ProbeError.TimedOut}. 실행 중인 그 배포판 세션이 있을 때만 부른다:
     * 멈춘 VM 을 깨우지 않게(FR-MON-02, R9).
     */
    Result<WslUsage, ProbeError> wsl(String distro, Duration timeout);
}
