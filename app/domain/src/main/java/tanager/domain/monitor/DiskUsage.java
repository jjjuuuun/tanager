package tanager.domain.monitor;

import java.util.Objects;

/**
 * 드라이브 하나의 디스크 사용량(FR-MON-01).
 *
 * @param root 표시용 루트: Windows 드라이브 {@code C:\} 또는 WSL 배포판 {@code /}
 */
public record DiskUsage(String root, Usage usage) {

    public DiskUsage {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(usage, "usage");
        if (root.isBlank()) {
            throw new IllegalArgumentException("디스크 루트가 비어 있다");
        }
    }
}
