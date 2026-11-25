package dev.tanager.domain.history;

import dev.tanager.domain.shared.Result;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

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

    private WorkspacePath(ShellEnvironment env, String path) {
        this.env = env;
        this.path = path;
        this.key = switch (env) {
            case ShellEnvironment.Windows _ -> path.toLowerCase(Locale.ROOT);
            case ShellEnvironment.Wsl _ -> path;
        };
    }

    /** {@code raw} 를 {@code env} 의 규칙으로 정규화한다. 빈 값은 {@link PathError.Blank}, 상대 경로는 {@link PathError.NotAbsolute}. */
    public static Result<WorkspacePath, PathError> of(ShellEnvironment env, String raw) {
        Objects.requireNonNull(env, "env");
        Objects.requireNonNull(raw, "raw");
        if (raw.isBlank()) {
            return Result.err(new PathError.Blank());
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

    private static Optional<String> normalizeWindows(String raw) {
        String s = raw.replace(WSL_SEP, WIN_SEP);
        if (s.startsWith(WIN_SEP + WIN_SEP)) {
            return normalizeUnc(s.substring(2));
        }
        if (isDriveAbsolute(s)) {
            String root = Character.toUpperCase(s.charAt(0)) + ":" + WIN_SEP;
            return Optional.of(root + String.join(WIN_SEP, resolveDots(segments(s.substring(2), WIN_SEP))));
        }
        return Optional.empty();
    }

    // "C:" 또는 "C:\…" 만 절대 경로다. "C:foo" 는 드라이브의 현재 디렉터리 기준 상대 경로라 거부한다.
    private static boolean isDriveAbsolute(String s) {
        return s.length() >= 2
                && isAsciiLetter(s.charAt(0))
                && s.charAt(1) == ':'
                && (s.length() == 2 || s.charAt(2) == '\\');
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    // \\host\share 가 UNC 의 루트다. '..' 은 share 위로 올라가지 않는다.
    private static Optional<String> normalizeUnc(String rest) {
        List<String> parts = segments(rest, WIN_SEP);
        if (parts.size() < 2 || isDot(parts.get(0)) || isDot(parts.get(1))) {
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
        return Optional.of(WSL_SEP + String.join(WSL_SEP, resolveDots(segments(raw, WSL_SEP))));
    }

    private static List<String> segments(String s, String separator) {
        return Arrays.stream(s.split(Pattern.quote(separator)))
                .filter(seg -> !seg.isEmpty())
                .toList();
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
