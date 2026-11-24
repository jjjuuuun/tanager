package dev.tanager.spike;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 터미널 출력 스트림에서 OSC 7(file://host/경로) · OSC 9;9("Windows 경로")를 찾아 cwd 를 알린다.
 * read 경계를 넘어 이어지는 시퀀스를 위해 상태를 유지한다. 끝: BEL(0x07) 또는 ST(ESC \).
 */
final class OscScanner {
    private enum State { TEXT, ESC, OSC, OSC_ESC }

    private static final int MAX = 4096;
    private final StringBuilder osc = new StringBuilder();
    private final Consumer<String> onCwd;
    private State state = State.TEXT;

    OscScanner(Consumer<String> onCwd) { this.onCwd = onCwd; }

    void feed(char[] buf, int off, int len) {
        for (int i = off; i < off + len; i++) feed(buf[i]);
    }

    void feed(CharSequence s) {
        for (int i = 0; i < s.length(); i++) feed(s.charAt(i));
    }

    private void feed(char c) {
        switch (state) {
            case TEXT -> { if (c == 0x1b) state = State.ESC; }
            case ESC -> {
                if (c == ']') { osc.setLength(0); state = State.OSC; }
                else state = c == 0x1b ? State.ESC : State.TEXT;
            }
            case OSC -> {
                if (c == 0x07) finish();
                else if (c == 0x1b) state = State.OSC_ESC;
                else if (osc.length() < MAX) osc.append(c);
                else state = State.TEXT; // 너무 길면 버림
            }
            case OSC_ESC -> {
                if (c == '\\') finish();
                else { state = State.TEXT; }
            }
        }
    }

    private void finish() {
        state = State.TEXT;
        parse(osc.toString()).ifPresent(onCwd);
    }

    static Optional<String> parse(String body) {
        if (body.startsWith("7;")) {
            String uri = body.substring(2);
            if (!uri.startsWith("file://")) return Optional.empty();
            int slash = uri.indexOf('/', "file://".length());
            if (slash < 0) return Optional.empty();
            String encoded = uri.substring(slash).replace("+", "%2B");
            return Optional.of(URLDecoder.decode(encoded, StandardCharsets.UTF_8));
        }
        if (body.startsWith("9;9;")) {
            String p = body.substring(4).trim();
            if (p.length() >= 2 && p.startsWith("\"") && p.endsWith("\"")) p = p.substring(1, p.length() - 1);
            return p.isEmpty() ? Optional.empty() : Optional.of(p);
        }
        return Optional.empty();
    }
}
