package tanager.domain.monitor;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 한 주기의 리소스 표본(SDD §5.6). 저장하지 않는 일시 값이다.
 *
 * <p>불변식: 0 ≤ cpuLoad ≤ 1, cores ≥ 1.
 *
 * @param at 표본을 잰 시각(툴팁의 마지막 갱신 시각)
 * @param disk 포커스 창 cwd 의 드라이브. 모르거나 끈 항목이면 비어 있다
 * @param wsl 포커스 창이 WSL 일 때만
 */
public record ResourceSample(
        Instant at, double cpuLoad, int cores, Usage memory, Optional<DiskUsage> disk, Optional<WslUsage> wsl) {

    public ResourceSample {
        Objects.requireNonNull(at, "at");
        CpuWindow.requireLoad(cpuLoad);
        if (cores < 1) {
            throw new IllegalArgumentException("코어 수는 1 이상이어야 한다: " + cores);
        }
        Objects.requireNonNull(memory, "memory");
        Objects.requireNonNull(disk, "disk");
        Objects.requireNonNull(wsl, "wsl");
    }

    /** 호스트 표본에 디스크 · WSL 을 붙인 표본. */
    public static ResourceSample of(Instant at, HostUsage host, Optional<DiskUsage> disk, Optional<WslUsage> wsl) {
        return new ResourceSample(at, host.cpuLoad(), host.cores(), host.memory(), disk, wsl);
    }
}
