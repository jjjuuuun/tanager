package tanager.domain.session;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 셸 출력 바이트에서 cwd 를 알리는 OSC 7({@code ESC ] 7;file://host/경로})과 OSC 9;9({@code ESC ] 9;9;경로})를 뽑는 스트리밍
 * 스캐너. 종료자는 BEL 또는 ST({@code ESC \}). 시퀀스가 조각 경계에서 잘려도 {@link Scan#next()} 가 이어 받는다(ADR-0015).
 *
 * <p>불변 값이다(동등성은 정의하지 않는다 — 같은 상태라도 다른 인스턴스일 수 있다). 불변식: 보관 중인 부분 본문 ≤ {@link
 * #MAX_PAYLOAD}. {@link #feed} 는 받은 배열을 바꾸지 않는다. 진행 중인 이스케이프 시퀀스가 없고 조각에 ESC 가 없으면 복사 · 할당 없이
 * 같은 결과를 돌려준다(NFR-01). 경로에 제어 문자(C0 · DEL)가 있는 보고는 버린다 — 경로가 될 수 없는 값이 이력으로 흘러가지 않게.
 */
public final class OscScanner {

    /** 이보다 긴 OSC 본문({@code ESC ]} 와 종료자 사이)은 버린다. */
    public static final int MAX_PAYLOAD = 4096;

    private static final byte ESC = 0x1B;
    private static final byte BEL = 0x07;
    private static final byte CAN = 0x18;
    private static final byte SUB = 0x1A;
    private static final byte OSC_START = ']';
    private static final byte ST_END = '\\';
    private static final String OSC7 = "7;";
    private static final String OSC99 = "9;9;";
    private static final String FILE_SCHEME = "file://";
    private static final byte[] NO_BODY = new byte[0];

    /** GROUND: 일반 출력 · ESCAPE: 일반 출력 중 ESC · BODY: OSC 본문 · BODY_ESCAPE: OSC 본문 중 ESC. */
    private enum Mode {
        GROUND,
        ESCAPE,
        BODY,
        BODY_ESCAPE
    }

    private static final OscScanner INITIAL = new OscScanner(Mode.GROUND, NO_BODY, false);
    private static final OscScanner AFTER_ESC = new OscScanner(Mode.ESCAPE, NO_BODY, false);
    private static final Scan IDLE = new Scan(INITIAL, List.of());

    private final Mode mode;
    // BODY · BODY_ESCAPE 에서 지금까지 모은 본문. 만든 뒤에는 바꾸지 않는다.
    private final byte[] body;
    // 이 OSC 는 종료자까지 버린다(길이 초과 · cwd 가 아닌 번호).
    private final boolean discarding;

    private OscScanner(Mode mode, byte[] body, boolean discarding) {
        this.mode = mode;
        this.body = body;
        this.discarding = discarding;
    }

    /** 진행 중인 시퀀스가 없는 스캐너. */
    public static OscScanner initial() {
        return INITIAL;
    }

    /** 한 번 먹인 결과: 다음 조각을 받을 스캐너와 이번 조각에서 끝난 보고(순서대로). */
    public record Scan(OscScanner next, List<CwdReport> reports) {
        public Scan {
            Objects.requireNonNull(next, "next");
            reports = List.copyOf(reports);
        }
    }

    /** 조각 하나를 먹인다. 이 스캐너와 {@code chunk} 는 바뀌지 않는다. */
    public Scan feed(byte[] chunk) {
        Objects.requireNonNull(chunk, "chunk");
        int from = 0;
        if (mode == Mode.GROUND) {
            from = indexOf(chunk, 0, chunk.length, ESC);
            if (from < 0) {
                return IDLE;
            }
        }
        // 이 호출 안에서만 쓰는 작업 상태. 밖으로는 새 불변 스캐너로만 나간다.
        Mode m = mode;
        byte[] buf = body.length == 0 ? NO_BODY : Arrays.copyOf(body, MAX_PAYLOAD);
        int len = body.length;
        boolean skip = discarding;
        List<CwdReport> reports = new ArrayList<>();
        for (int i = from; i < chunk.length; i++) {
            if (m == Mode.GROUND) {
                // 출력 대부분은 이스케이프 사이의 글자다 — 다음 ESC 로 바로 건너뛴다(#93, 50MB 출력 중 비용)
                int esc = indexOf(chunk, i, chunk.length, ESC);
                if (esc < 0) {
                    break;
                }
                i = esc;
                m = Mode.ESCAPE;
                continue;
            }
            byte b = chunk[i];
            switch (m) {
                case GROUND -> {
                    if (b == ESC) {
                        m = Mode.ESCAPE;
                    }
                }
                case ESCAPE -> {
                    m = afterEscape(b);
                    len = 0;
                    skip = false;
                }
                case BODY -> {
                    if (b == BEL) {
                        completed(buf, len, skip).ifPresent(reports::add);
                        m = Mode.GROUND;
                    } else if (b == ESC) {
                        m = Mode.BODY_ESCAPE;
                    } else if (b == CAN || b == SUB) {
                        m = Mode.GROUND;
                    } else if (!skip) {
                        if (len == MAX_PAYLOAD) {
                            skip = true;
                        } else if (len == 0 && b != OSC7.charAt(0) && b != OSC99.charAt(0)) {
                            // 제목 · 하이퍼링크 같은 다른 번호는 버퍼를 만들지 않고 종료자까지 버린다
                            skip = true;
                        } else {
                            if (buf.length == 0) {
                                buf = new byte[MAX_PAYLOAD];
                            }
                            buf[len++] = b;
                            skip = len <= OSC99.length() && !couldBeCwd(buf, len);
                        }
                    }
                }
                case BODY_ESCAPE -> {
                    if (b == ST_END) {
                        completed(buf, len, skip).ifPresent(reports::add);
                        m = Mode.GROUND;
                    } else {
                        // ESC 뒤 다른 글자는 OSC 를 취소하고, 그 ESC 는 새 이스케이프 시퀀스의 시작이다(xterm 과 같다).
                        m = afterEscape(b);
                        len = 0;
                        skip = false;
                    }
                }
            }
        }
        OscScanner next = switch (m) {
            case GROUND -> INITIAL;
            case ESCAPE -> AFTER_ESC;
            case BODY, BODY_ESCAPE -> new OscScanner(m, skip ? NO_BODY : Arrays.copyOf(buf, len), skip);
        };
        if (next == INITIAL && reports.isEmpty()) {
            return IDLE;
        }
        return new Scan(next, reports);
    }

    private static Mode afterEscape(byte b) {
        if (b == OSC_START) {
            return Mode.BODY;
        }
        return b == ESC ? Mode.ESCAPE : Mode.GROUND;
    }

    // 앞부분이 "7;" 또는 "9;9;" 의 앞부분과 맞는 동안만 본문을 모은다(제목 OSC 같은 것은 모으지 않는다).
    private static boolean couldBeCwd(byte[] buf, int len) {
        return sharesPrefix(buf, len, OSC7) || sharesPrefix(buf, len, OSC99);
    }

    private static boolean sharesPrefix(byte[] buf, int len, String ascii) {
        return matches(buf, 0, ascii, Math.min(len, ascii.length()));
    }

    // 종료자를 만난 본문. 버리는 중이었으면 보고가 없다.
    private static Optional<CwdReport> completed(byte[] buf, int len, boolean skip) {
        return skip ? Optional.empty() : parse(buf, len);
    }

    private static Optional<CwdReport> parse(byte[] buf, int len) {
        if (startsWith(buf, 0, len, OSC7)) {
            return fileUri(buf, OSC7.length(), len);
        }
        if (startsWith(buf, 0, len, OSC99)) {
            return windowsPath(buf, OSC99.length(), len);
        }
        return Optional.empty();
    }

    // NUL · 줄바꿈 같은 제어 문자는 어느 OS 에서도 정상 경로가 아니다 — 터미널에 찍힌 임의 바이트가 이력을 더럽히지 않게 버린다.
    private static Optional<String> withoutControls(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return Optional.empty();
            }
        }
        return Optional.of(s);
    }

    // file://host/경로 — host 는 비어 있을 수 있고 경로는 '/' 부터 끝까지다.
    private static Optional<CwdReport> fileUri(byte[] buf, int from, int to) {
        if (!startsWith(buf, from, to, FILE_SCHEME)) {
            return Optional.empty();
        }
        int hostStart = from + FILE_SCHEME.length();
        int slash = indexOf(buf, hostStart, to, (byte) '/');
        if (slash < 0) {
            return Optional.empty();
        }
        Optional<String> host = percentDecodedUtf8(buf, hostStart, slash).flatMap(OscScanner::withoutControls);
        Optional<String> path = percentDecodedUtf8(buf, slash, to).flatMap(OscScanner::withoutControls);
        return host.flatMap(h -> path.map(p -> new CwdReport.FileUri(h, p)));
    }

    // PowerShell 프롬프트는 경로를 따옴표로 감싸고 cmd 의 $P 는 감싸지 않는다.
    private static Optional<CwdReport> windowsPath(byte[] buf, int from, int to) {
        return strictUtf8(buf, from, to)
                .flatMap(OscScanner::withoutControls)
                .map(OscScanner::unquote)
                .map(CwdReport.WindowsPath::new);
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    // %XX 는 바이트로 바꾸고, 잘못된 퍼센트 표기나 UTF-8 이 아닌 결과는 버린다.
    private static Optional<String> percentDecodedUtf8(byte[] buf, int from, int to) {
        byte[] out = new byte[to - from];
        int n = 0;
        for (int i = from; i < to; i++) {
            if (buf[i] != '%') {
                out[n++] = buf[i];
                continue;
            }
            int hi = i + 2 < to ? hexValue(buf[i + 1]) : -1;
            int lo = hi >= 0 ? hexValue(buf[i + 2]) : -1;
            if (lo < 0) {
                return Optional.empty();
            }
            out[n++] = (byte) (hi << 4 | lo);
            i += 2;
        }
        return strictUtf8(out, 0, n);
    }

    private static int hexValue(byte b) {
        if (b >= '0' && b <= '9') {
            return b - '0';
        }
        if (b >= 'a' && b <= 'f') {
            return b - 'a' + 10;
        }
        if (b >= 'A' && b <= 'F') {
            return b - 'A' + 10;
        }
        return -1;
    }

    // JDK 디코더는 잘못된 바이트를 U+FFFD 로 바꾼다. 다시 인코딩해 원본과 같을 때만 받아 엄격하게 해석한다(ADR-0015).
    private static Optional<String> strictUtf8(byte[] bytes, int from, int to) {
        String s = new String(bytes, from, to - from, StandardCharsets.UTF_8);
        byte[] back = s.getBytes(StandardCharsets.UTF_8);
        return Arrays.equals(back, 0, back.length, bytes, from, to) ? Optional.of(s) : Optional.empty();
    }

    private static boolean startsWith(byte[] buf, int from, int to, String ascii) {
        return to - from >= ascii.length() && matches(buf, from, ascii, ascii.length());
    }

    // buf[from..from+n) 가 ascii 의 앞 n 글자와 같은가.
    private static boolean matches(byte[] buf, int from, String ascii, int n) {
        for (int k = 0; k < n; k++) {
            if (buf[from + k] != ascii.charAt(k)) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(byte[] buf, int from, int to, byte target) {
        for (int i = from; i < to; i++) {
            if (buf[i] == target) {
                return i;
            }
        }
        return -1;
    }
}
