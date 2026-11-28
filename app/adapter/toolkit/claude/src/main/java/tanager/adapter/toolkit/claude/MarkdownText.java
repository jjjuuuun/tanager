package tanager.adapter.toolkit.claude;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 메모리 · 규칙 Markdown 의 줄 수 · 첫 제목 · {@code @import} 수. 앞 {@link #MAX_BYTES} 바이트만 읽는다. 맨 앞의 프런트매터와 코드 블록
 * 안은 제목 · import 로 보지 않는다. import 는 세기만 하고 따라가지 않는다.
 *
 * @param lines 줄 수. 상한을 넘으면 앞부분 줄 수 앞에 {@code >}
 * @param heading 첫 ATX 제목({@code #} ~ {@code ######}), 한 줄로 펴고 {@link #MAX_HEADING_CODE_POINTS} 로 자른다
 * @param imports {@code @경로} 수
 */
record MarkdownText(String lines, Optional<String> heading, int imports) {

    /** 한 파일에서 읽는 최대 바이트. */
    static final int MAX_BYTES = 1024 * 1024;

    /** 제목의 최대 길이(코드 포인트). */
    static final int MAX_HEADING_CODE_POINTS = 80;

    private static final String FENCE = "---";
    private static final char BOM = '﻿';
    private static final Pattern HEADING = Pattern.compile("^ {0,3}#{1,6}(?:[ \\t]+(.*?))?(?:[ \\t]+#+)?[ \\t]*$");
    private static final Pattern CODE_FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})");
    // Claude Code 문서: "@path/to/import". 앞이 줄 처음 · 공백일 때만 — 메일 주소(dev@example.com)는 아니다
    private static final Pattern IMPORT = Pattern.compile("(?<!\\S)@\\S");

    MarkdownText {
        Objects.requireNonNull(lines, "lines");
        Objects.requireNonNull(heading, "heading");
    }

    /** 스트림을 읽고 닫는다. */
    static MarkdownText read(InputStream raw) throws IOException {
        return of(head(raw));
    }

    /** 스트림의 앞 {@link #MAX_BYTES} + 1 바이트(넘는지 알기 위한 1). 스트림을 닫는다. */
    static byte[] head(InputStream raw) throws IOException {
        try (InputStream in = new BoundedInputStream(raw, MAX_BYTES + 1L)) {
            return in.readAllBytes();
        }
    }

    /** {@link #head} 가 읽은 바이트에서. */
    static MarkdownText of(byte[] head) {
        boolean truncated = head.length > MAX_BYTES;
        String text = new String(head, 0, Math.min(head.length, MAX_BYTES), StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == BOM) {
            text = text.substring(1);
        }
        String[] lines = text.split("\n", -1);
        int count = text.isEmpty() ? 0 : (text.endsWith("\n") ? lines.length - 1 : lines.length);
        Optional<String> heading = Optional.empty();
        int imports = 0;
        String fence = "";
        for (int i = bodyStart(lines); i < count; i++) {
            String line = stripCr(lines[i]);
            if (!fence.isEmpty()) {
                if (closes(line, fence)) {
                    fence = "";
                }
                continue;
            }
            Matcher open = CODE_FENCE.matcher(line);
            if (open.find()) {
                fence = open.group(1);
                continue;
            }
            if (heading.isEmpty()) {
                heading = headingOf(line);
            }
            imports += importsIn(withoutCodeSpans(line));
        }
        return new MarkdownText((truncated ? ">" : "") + count, heading, imports);
    }

    // 닫힌 프런트매터가 맨 앞에 있으면 그 다음 줄부터(Claude Code 는 규칙의 프런트매터를 떼고 읽는다)
    private static int bodyStart(String[] lines) {
        if (lines.length == 0 || !isFence(lines[0])) {
            return 0;
        }
        for (int i = 1; i < lines.length; i++) {
            if (isFence(lines[i])) {
                return i + 1;
            }
        }
        return 0;
    }

    private static boolean isFence(String line) {
        return stripCr(line).stripTrailing().equals(FENCE);
    }

    private static boolean closes(String line, String fence) {
        String trimmed = line.strip();
        char mark = fence.charAt(0);
        if (trimmed.length() < fence.length()
                || line.length() - line.stripLeading().length() > 3) {
            return false;
        }
        return trimmed.chars().allMatch(c -> c == mark);
    }

    private static Optional<String> headingOf(String line) {
        Matcher matcher = HEADING.matcher(line);
        if (!matcher.matches() || matcher.group(1) == null) {
            return Optional.empty();
        }
        return Frontmatter.clean(matcher.group(1), MAX_HEADING_CODE_POINTS);
    }

    private static int importsIn(String line) {
        Matcher matcher = IMPORT.matcher(line);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    // 같은 길이의 백틱 묶음 사이(코드 스팬)를 공백으로 바꾼다. 짝이 없으면 글자 그대로다
    private static String withoutCodeSpans(String line) {
        StringBuilder out = new StringBuilder(line.length());
        int i = 0;
        while (i < line.length()) {
            if (line.charAt(i) != '`') {
                out.append(line.charAt(i++));
                continue;
            }
            int run = runLength(line, i);
            int close = closingRun(line, i + run, run);
            if (close < 0) {
                out.append(line, i, i + run);
                i += run;
            } else {
                out.append(" ");
                i = close + run;
            }
        }
        return out.toString();
    }

    private static int closingRun(String line, int from, int length) {
        int i = from;
        while (i < line.length()) {
            if (line.charAt(i) == '`') {
                int run = runLength(line, i);
                if (run == length) {
                    return i;
                }
                i += run;
            } else {
                i++;
            }
        }
        return -1;
    }

    private static int runLength(String line, int from) {
        int i = from;
        while (i < line.length() && line.charAt(i) == '`') {
            i++;
        }
        return i - from;
    }

    private static String stripCr(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }
}
