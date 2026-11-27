package tanager.platform.windows;

import java.util.Optional;

/** 명령 출력의 숫자 칸 읽기. */
final class Numbers {
    // 1K 블록 · kB 값을 바이트로 바꿔도 넘치지 않는 범위
    private static final long MAX = Long.MAX_VALUE / 1024;

    private Numbers() {}

    /** 0 이상의 10진 정수. 숫자가 아니거나 너무 크면 비어 있다. */
    static Optional<Long> parseNonNegative(String text) {
        if (text.isEmpty() || !text.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return Optional.empty();
        }
        try {
            long value = Long.parseLong(text);
            return value <= MAX ? Optional.of(value) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
