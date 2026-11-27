package tanager.platform.windows;

import java.util.Optional;
import tanager.application.port.out.ProbeError;
import tanager.domain.monitor.Usage;
import tanager.domain.shared.Result;

/**
 * WSL {@code /proc/meminfo} 에서 VM 메모리를 읽는다(FR-MON-02). used = MemTotal − MemAvailable. WSL2 의 MemTotal 은 {@code .wslconfig}
 * 의 {@code memory} 를 반영한다(2.5.10 실측: memory=11GB → MemTotal 11216076 kB, 커널 예약분만 빠진다) — 그래서 한도로 그대로 쓴다.
 */
final class MeminfoParser {
    private static final long KIB = 1024;

    private MeminfoParser() {}

    /** {@code text} 의 다른 줄(뒤에 붙은 df 출력 등)은 무시한다. */
    static Result<Usage, ProbeError> parse(String text) {
        Optional<Long> total = field(text, "MemTotal");
        Optional<Long> available = field(text, "MemAvailable");
        if (total.isEmpty() || available.isEmpty()) {
            return Result.err(new ProbeError.Unreadable("meminfo 에 MemTotal · MemAvailable 이 없다"));
        }
        if (total.get() <= 0) {
            return Result.err(new ProbeError.Unreadable("MemTotal 이 0 이다"));
        }
        long used = Math.max(0, total.get() - available.get());
        return Result.ok(new Usage(used * KIB, total.get() * KIB));
    }

    // "MemTotal:       11216076 kB" → 11216076
    private static Optional<Long> field(String text, String name) {
        for (String line : text.split("\\R")) {
            String[] parts = line.strip().split("\\s+");
            if (parts.length >= 2 && parts[0].equals(name + ":")) {
                return Numbers.parseNonNegative(parts[1]);
            }
        }
        return Optional.empty();
    }
}
