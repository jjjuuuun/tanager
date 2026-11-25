package dev.tanager.architecture;

/** 소스에서 문자열 리터럴 {@code "os.name"} 을 센다(주석 · 문자 리터럴은 {@link ColorLiterals#stringLiterals} 가 건너뛴다). */
final class OsNameReads {
    private OsNameReads() {}

    static int count(String source) {
        int count = 0;
        for (String literal : ColorLiterals.stringLiterals(source)) {
            if (literal.equals("os.name")) {
                count++;
            }
        }
        return count;
    }
}
