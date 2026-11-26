package dev.tanager.architecture;

import java.nio.file.Path;

/** 소스에서 문자열 리터럴 {@code "os.name"} 을 센다(주석 · 문자 리터럴은 {@link ColorLiterals#stringLiterals} 가 건너뛴다). */
final class OsNameReads {
    private OsNameReads() {}

    /** {@code src/main/java} 아래이고 build 산출물이 아닌 제품 소스인가(경로 구분자와 상관없이). */
    static boolean isProductionSource(Path file) {
        String p = file.toString().replace('\\', '/');
        return p.contains("/src/main/java/") && !p.contains("/build/");
    }

    /** app/bootstrap 모듈의 파일인가(테스트는 app/architecture 에서 돌아 app 이 ".." 다). */
    static boolean isBootstrap(Path file) {
        String p = file.toString().replace('\\', '/');
        return p.startsWith("../bootstrap/") || p.contains("/app/bootstrap/");
    }

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
