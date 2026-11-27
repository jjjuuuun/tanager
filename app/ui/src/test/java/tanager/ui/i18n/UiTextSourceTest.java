package tanager.ui.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ui 코드에 화면용 한글 문자열이 직접 들어가지 않게 지킨다(#136). 로그 · 예외 메시지(개발자용)와 글꼴 확인용 견본은 허용한다. 화면 글자는
 * {@link Msg} 와 번들로만 쓴다.
 */
class UiTextSourceTest {
    private static final Path SOURCES = Path.of("src/main/java");
    private static final Pattern LITERAL = Pattern.compile("\"(?:[^\"\\\\\\n]|\\\\.)*\"");
    private static final Pattern HANGUL = Pattern.compile("[\\uAC00-\\uD7A3\\u3131-\\u318E]");
    private static final List<String> ALLOWED = List.of("LOG.log(", "Exception(", "Result.err(", "HANGUL_SAMPLE");

    @Test
    @DisplayName("ui main 코드에 화면용 한글 문자열 리터럴이 없다(로그 · 예외 제외)")
    void noHangulScreenText() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = withoutComments(Files.readString(file, StandardCharsets.UTF_8));
                Matcher literal = LITERAL.matcher(code);
                while (literal.find()) {
                    if (HANGUL.matcher(literal.group()).find() && !allowed(statementBefore(code, literal.start()))) {
                        offenders.add(SOURCES.relativize(file) + ": " + literal.group());
                    }
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    @DisplayName("검사가 실제로 소스를 읽는다(경로가 틀려 0건으로 통과하지 않게)")
    void scansSources() throws IOException {
        try (Stream<Path> files = Files.walk(SOURCES)) {
            assertThat(files.filter(p -> p.toString().endsWith(".java")).count())
                    .isGreaterThan(20);
        }
    }

    // 리터럴이 속한 문장: 앞쪽의 가장 가까운 ; { } 다음부터
    private static String statementBefore(String code, int at) {
        int start = at;
        while (start > 0 && ";{}".indexOf(code.charAt(start - 1)) < 0) {
            start--;
        }
        return code.substring(start, at);
    }

    private static boolean allowed(String statement) {
        return ALLOWED.stream().anyMatch(statement::contains);
    }

    // 주석을 공백으로 지운다. 문자열 안의 // 는 건드리지 않는다
    private static String withoutComments(String code) {
        StringBuilder out = new StringBuilder(code.length());
        int i = 0;
        while (i < code.length()) {
            char c = code.charAt(i);
            if (c == '"') {
                int end = i + 1;
                while (end < code.length() && code.charAt(end) != '"' && code.charAt(end) != '\n') {
                    end += code.charAt(end) == '\\' ? 2 : 1;
                }
                end = Math.min(end + 1, code.length());
                out.append(code, i, end);
                i = end;
            } else if (code.startsWith("//", i)) {
                while (i < code.length() && code.charAt(i) != '\n') {
                    i++;
                }
            } else if (code.startsWith("/*", i)) {
                int end = code.indexOf("*/", i + 2);
                i = end < 0 ? code.length() : end + 2;
                out.append(' ');
            } else if (c == '\'' && i + 2 < code.length()) {
                int end = code.indexOf('\'', i + (code.charAt(i + 1) == '\\' ? 3 : 2));
                end = end < 0 ? i + 1 : end + 1;
                out.append(code, i, end);
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
