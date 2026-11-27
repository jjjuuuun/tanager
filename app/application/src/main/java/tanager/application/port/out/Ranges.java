package tanager.application.port.out;

/** 설정 값 타입들이 함께 쓰는 범위 검사. */
final class Ranges {
    private Ranges() {}

    static void require(String name, long value, long min, long max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " 는 " + min + "~" + max + " 이어야 한다: " + value);
        }
    }

    // NaN 도 거부한다(비교가 모두 거짓)
    static void require(String name, double value, double min, double max) {
        if (!(value >= min && value <= max)) {
            throw new IllegalArgumentException(name + " 는 " + min + "~" + max + " 이어야 한다: " + value);
        }
    }
}
