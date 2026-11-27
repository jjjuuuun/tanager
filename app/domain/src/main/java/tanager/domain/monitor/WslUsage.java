package tanager.domain.monitor;

import java.util.Objects;

/**
 * WSL 배포판 하나에서 본 VM 메모리와 그 배포판 {@code /} 의 디스크(FR-MON-02).
 *
 * @param vmMemory WSL VM 메모리(사용 / 한도 — {@code .wslconfig} 의 memory 가 반영된 {@code MemTotal})
 * @param rootDisk 배포판 {@code /} 의 디스크
 */
public record WslUsage(String distro, Usage vmMemory, Usage rootDisk) {

    public WslUsage {
        Objects.requireNonNull(distro, "distro");
        Objects.requireNonNull(vmMemory, "vmMemory");
        Objects.requireNonNull(rootDisk, "rootDisk");
        if (distro.isBlank()) {
            throw new IllegalArgumentException("배포판 이름이 비어 있다");
        }
    }
}
