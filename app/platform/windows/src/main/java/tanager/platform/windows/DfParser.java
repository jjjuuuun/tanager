package tanager.platform.windows;

import java.util.List;
import java.util.Optional;
import tanager.application.port.out.ProbeError;
import tanager.domain.monitor.Usage;
import tanager.domain.shared.Result;

/**
 * WSL {@code df -kP /} 의 데이터 줄을 읽는다(FR-MON-02). {@code -P} 라 장치 이름이 길어도 한 줄이고 값은 1K 블록이다. 머리줄은 로캘마다
 * 다르므로(ko_KR: "파일 시스템 1024-블록 …") 보지 않고, 마운트 위치가 {@code /} 이고 숫자 칸이 있는 줄을 찾는다.
 */
final class DfParser {
    private static final long KIB = 1024;
    // 장치 · 크기 · 사용 · 가용 · 비율 · 마운트 위치
    private static final int FIELDS = 6;

    private DfParser() {}

    /** {@code text} 의 다른 줄(앞의 meminfo 출력 등)은 무시한다. */
    static Result<Usage, ProbeError> parse(String text) {
        Optional<Usage> usage = Optional.empty();
        boolean zeroSize = false;
        for (String line : text.split("\\R")) {
            List<String> fields = List.of(line.strip().split("\\s+"));
            if (fields.size() < FIELDS || !fields.getLast().equals("/")) {
                continue;
            }
            int n = fields.size();
            Optional<Long> size = Numbers.parseNonNegative(fields.get(n - 5));
            Optional<Long> used = Numbers.parseNonNegative(fields.get(n - 4));
            if (size.isEmpty() || used.isEmpty()) {
                continue;
            }
            if (size.get() == 0) {
                zeroSize = true;
                continue;
            }
            usage = Optional.of(new Usage(Math.min(used.get(), size.get()) * KIB, size.get() * KIB));
        }
        if (usage.isPresent()) {
            return Result.ok(usage.get());
        }
        return Result.err(new ProbeError.Unreadable(zeroSize ? "df 의 / 크기가 0 이다" : "df 에 / 데이터 줄이 없다"));
    }
}
