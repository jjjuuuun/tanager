package tanager.domain.history;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import tanager.domain.shared.Result;

/**
 * 환경별로 정규화된 절대 작업 경로. 경로를 {@code String} 으로 비교하지 않도록 비교 규칙을 이 타입이 가진다 —
 * Windows 는 대소문자를 무시하고 WSL 은 구분한다. {@link #path()} 는 입력의 대소문자를 보존한다.
 *
 * <p>정규화는 순수 문자열 처리다(domain 은 파일시스템을 보지 않는다). 심볼릭 링크는 해소하지 않는다.
 */
public final class WorkspacePath {

    private static final String WIN_SEP = "\\";
    private static final String WSL_SEP = "/";

    private final ShellEnvironment env;
    private final String path;
    private final String key;
    private final String root;
    private final List<String> segments;

    private WorkspacePath(ShellEnvironment env, String path) {
        this.env = env;
        this.path = path;
        this.key = switch (env) {
            case ShellEnvironment.Windows _ -> path.toLowerCase(Locale.ROOT);
            case ShellEnvironment.Wsl _ -> path;
        };
        this.root = rootOf(env, path);
        this.segments = split(path.substring(root.length()), separator(env));
    }

    /**
     * {@code raw} 를 {@code env} 의 규칙으로 정규화한다. 빈 값은 {@link PathError.Blank}, 안전하지 않은 문자가 있으면 {@link
     * PathError.UnsafeCharacter}, 상대 경로는 {@link PathError.NotAbsolute}.
     *
     * <p>큰따옴표와 제어 문자(0x00–0x1F, 0x7F)는 정규화 전에 거부하고, WSL 은 역슬래시도 거부한다(Windows 는 구분자라 허용). 이 경로가
     * wsl.exe {@code --cd} 같은 Windows 명령줄 인자로 쓰이는데, pty4j 의 {@code joinCmdArgs} 는 공백이 있는 원소를 큰따옴표로 감싸기만
     * 하고 원소 안의 {@code "} 와 끝 {@code \} 를 이스케이프하지 않는다 — 인자가 갈라져 {@code --} 뒤 셸 명령으로 주입된다(#107). 이력 ·
     * 복원 · 셸이 알린 경로가 모두 여기를 거치므로 출처에서 막는다.
     */
    public static Result<WorkspacePath, PathError> of(ShellEnvironment env, String raw) {
        Objects.requireNonNull(env, "env");
        Objects.requireNonNull(raw, "raw");
        if (raw.isBlank()) {
            return Result.err(new PathError.Blank());
        }
        if (hasUnsafeCharacter(env, raw)) {
            return Result.err(new PathError.UnsafeCharacter(raw));
        }
        Optional<String> normalized = switch (env) {
            case ShellEnvironment.Windows _ -> normalizeWindows(raw);
            case ShellEnvironment.Wsl _ -> normalizeWsl(raw);
        };
        return normalized
                .<Result<WorkspacePath, PathError>>map(p -> Result.ok(new WorkspacePath(env, p)))
                .orElseGet(() -> Result.err(new PathError.NotAbsolute(raw)));
    }

    /** 이 경로가 속한 환경. */
    public ShellEnvironment env() {
        return env;
    }

    /** 정규화된 경로 문자열(입력의 대소문자 보존). */
    public String path() {
        return path;
    }

    /**
     * 루트 부분. WSL 은 {@code /}, 드라이브는 {@code C:\}(대문자), UNC 는 {@code \\host\share}(입력 대소문자 보존). 표시용이다 —
     * 비교 · 묶음 키로 쓰지 말고 {@link WorkspacePath} 자체(또는 {@link #parent()} 사슬)를 쓴다(Windows 는 대소문자를 무시한다).
     */
    public String root() {
        return root;
    }

    /** 루트 아래의 이름들(대소문자 보존, 표시용 — 비교에는 쓰지 않는다). 수정할 수 없다. 루트 자체면 빈 리스트. */
    public List<String> segments() {
        return segments;
    }

    /** 한 단계 위 경로(같은 환경). 루트면 empty. */
    public Optional<WorkspacePath> parent() {
        if (segments.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new WorkspacePath(
                env, join(root, segments.subList(0, segments.size() - 1), separator(env))));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof WorkspacePath other && env.equals(other.env) && key.equals(other.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(env, key);
    }

    @Override
    public String toString() {
        return "WorkspacePath[" + env + " " + path + "]";
    }

    private static boolean hasUnsafeCharacter(ShellEnvironment env, String raw) {
        boolean backslashUnsafe = switch (env) {
            case ShellEnvironment.Windows _ -> false;
            case ShellEnvironment.Wsl _ -> true;
        };
        return raw.chars().anyMatch(c -> c == '"' || c < 0x20 || c == 0x7f || (backslashUnsafe && c == '\\'));
    }

    private static String separator(ShellEnvironment env) {
        return switch (env) {
            case ShellEnvironment.Windows _ -> WIN_SEP;
            case ShellEnvironment.Wsl _ -> WSL_SEP;
        };
    }

    // path 는 이미 정규화돼 있다: "/…" · "C:\…" · "\\host\share…".
    private static String rootOf(ShellEnvironment env, String path) {
        return switch (env) {
            case ShellEnvironment.Wsl _ -> WSL_SEP;
            case ShellEnvironment.Windows _ -> {
                if (!path.startsWith(WIN_SEP + WIN_SEP)) {
                    yield path.substring(0, 3);
                }
                List<String> parts = split(path.substring(2), WIN_SEP);
                yield WIN_SEP + WIN_SEP + parts.get(0) + WIN_SEP + parts.get(1);
            }
        };
    }

    // 드라이브 · WSL 루트는 구분자로 끝나고 UNC 루트는 끝나지 않는다.
    private static String join(String root, List<String> segments, String separator) {
        if (segments.isEmpty()) {
            return root;
        }
        String glue = root.endsWith(separator) ? "" : separator;
        return root + glue + String.join(separator, segments);
    }

    private static Optional<String> normalizeWindows(String raw) {
        String s = raw.replace(WSL_SEP, WIN_SEP);
        if (s.startsWith(WIN_SEP + WIN_SEP)) {
            return normalizeUnc(s.substring(2));
        }
        if (isDriveAbsolute(s)) {
            String root = Character.toUpperCase(s.charAt(0)) + ":" + WIN_SEP;
            return Optional.of(root + String.join(WIN_SEP, resolveDots(split(s.substring(2), WIN_SEP))));
        }
        return Optional.empty();
    }

    // "C:\…" 만 절대 경로다. "C:" · "C:foo" 는 그 드라이브의 현재 디렉터리 기준 상대 경로라 거부한다.
    private static boolean isDriveAbsolute(String s) {
        return s.length() >= 3 && isAsciiLetter(s.charAt(0)) && s.charAt(1) == ':' && s.charAt(2) == '\\';
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    // \\host\share 가 UNC 의 루트다. '..' 은 share 위로 올라가지 않는다.
    // 확장 길이 접두 \\?\ 와 장치 경로 \\.\ 는 작업 경로로 받지 않는다(셸이 알려 주는 경로에는 없다).
    private static Optional<String> normalizeUnc(String rest) {
        List<String> parts = split(rest, WIN_SEP);
        if (parts.size() < 2 || isDot(parts.get(0)) || parts.get(0).equals("?") || isDot(parts.get(1))) {
            return Optional.empty();
        }
        StringBuilder sb = new StringBuilder(WIN_SEP + WIN_SEP)
                .append(parts.get(0))
                .append(WIN_SEP)
                .append(parts.get(1));
        for (String seg : resolveDots(parts.subList(2, parts.size()))) {
            sb.append(WIN_SEP).append(seg);
        }
        return Optional.of(sb.toString());
    }

    private static Optional<String> normalizeWsl(String raw) {
        if (!raw.startsWith(WSL_SEP)) {
            return Optional.empty();
        }
        return Optional.of(WSL_SEP + String.join(WSL_SEP, resolveDots(split(raw, WSL_SEP))));
    }

    // 빈 세그먼트는 버린다. 정규식을 쓰지 않는다 — parent() 사슬마다 불려 트리 투영의 대부분을 차지한다.
    private static List<String> split(String s, String separator) {
        List<String> out = new ArrayList<>();
        int start = 0;
        while (start < s.length()) {
            int end = s.indexOf(separator, start);
            if (end < 0) {
                end = s.length();
            }
            if (end > start) {
                out.add(s.substring(start, end));
            }
            start = end + separator.length();
        }
        return List.copyOf(out);
    }

    private static boolean isDot(String seg) {
        return seg.equals(".") || seg.equals("..");
    }

    private static List<String> resolveDots(List<String> segments) {
        List<String> out = new ArrayList<>();
        for (String seg : segments) {
            if (seg.equals("..")) {
                if (!out.isEmpty()) {
                    out.removeLast();
                }
            } else if (!seg.equals(".")) {
                out.add(seg);
            }
        }
        return out;
    }
}
