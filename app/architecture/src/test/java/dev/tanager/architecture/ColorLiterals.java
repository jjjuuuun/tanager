package dev.tanager.architecture;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java 소스의 문자열 리터럴(텍스트 블록 포함)에서 색 문자열({@code #RGB} ~ {@code #RRGGBBAA})을 찾는다.
 * 바이트코드 규칙(ArchUnit)은 FlatLaf 스타일 문자열 같은 문자열 속 색을 보지 못해 소스를 직접 읽는다. 주석은 보지 않는다.
 */
final class ColorLiterals {
    private static final Pattern COLOR = Pattern.compile("#[0-9A-Fa-f]{3,8}\\b");

    private ColorLiterals() {}

    /** 색 문자열이 든 문자열 리터럴 안의 색 조각들. */
    static List<String> find(String source) {
        List<String> found = new ArrayList<>();
        for (String literal : stringLiterals(source)) {
            Matcher m = COLOR.matcher(literal);
            while (m.find()) {
                found.add(m.group());
            }
        }
        return found;
    }

    /** 주석 · 문자 리터럴을 건너뛰며 문자열 · 텍스트 블록의 내용(이스케이프는 그대로)을 모은다. */
    static List<String> stringLiterals(String s) {
        List<String> literals = new ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            if (s.startsWith("//", i)) {
                i = indexOrEnd(s, "\n", i);
            } else if (s.startsWith("/*", i)) {
                i = indexOrEnd(s, "*/", i + 2) + 2;
            } else if (s.startsWith("\"\"\"", i)) {
                int end = closing(s, "\"\"\"", i + 3);
                literals.add(s.substring(i + 3, Math.min(end, n)));
                i = end + 3;
            } else if (s.charAt(i) == '"' || s.charAt(i) == '\'') {
                String quote = String.valueOf(s.charAt(i));
                int end = closing(s, quote, i + 1);
                if (quote.equals("\"")) {
                    literals.add(s.substring(i + 1, Math.min(end, n)));
                }
                i = end + 1;
            } else {
                i++;
            }
        }
        return literals;
    }

    private static int indexOrEnd(String s, String token, int from) {
        int at = s.indexOf(token, from);
        return at < 0 ? s.length() : at;
    }

    /** 백슬래시 이스케이프를 건너뛰며 닫는 따옴표의 위치. */
    private static int closing(String s, String quote, int from) {
        int i = from;
        while (i < s.length()) {
            if (s.charAt(i) == '\\') {
                i += 2;
            } else if (s.startsWith(quote, i)) {
                return i;
            } else {
                i++;
            }
        }
        return s.length();
    }
}
