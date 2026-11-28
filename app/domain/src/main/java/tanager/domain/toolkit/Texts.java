package tanager.domain.toolkit;

/** 도구함 값 객체가 함께 쓰는 문자열 검사. */
final class Texts {
    private Texts() {}

    static boolean hasControl(String s) {
        return s.codePoints().anyMatch(Character::isISOControl);
    }

    static int codePoints(String s) {
        return s.codePointCount(0, s.length());
    }
}
