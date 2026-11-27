package tanager.domain.history;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import tanager.domain.shared.Result;

/**
 * 이력 제외 규칙 하나 — 환경을 아는 glob(FR-HIS-09). {@code **} 는 0개 이상의 세그먼트, {@code *} 는 세그먼트 안의 0자 이상, {@code ?} 는
 * 한 글자다. 대소문자는 대상 경로의 {@link WorkspacePath} 규칙을 따른다(Windows 무시, WSL 구분).
 *
 * <p>적용 환경은 시작으로 정한다. {@code /} 로 시작하면 WSL, 드라이브({@code C:\} · {@code C:/})나 UNC({@code \\})로 시작하면 Windows,
 * {@code **} 로 시작하면 모든 환경이다. Windows · 모든 환경에서는 {@code /} 와 {@code \} 를 같은 구분자로 본다.
 *
 * <p>{@code java.nio.file.PathMatcher} 를 쓰지 않는다 — 규칙이 OS 마다 다르고, 도는 OS 가 아니라 경로의 환경으로 판단해야 한다. 같음은 원문으로
 * 가린다.
 */
public final class PathPattern {
    private static final String DOUBLE_STAR = "**";
    private static final String WSL_SEPARATORS = "/";
    private static final String ANY_SEPARATORS = "/\\";

    private final String source;
    private final Scope scope;
    private final List<String> segments;

    private PathPattern(String source, Scope scope, List<String> segments) {
        this.source = source;
        this.scope = scope;
        this.segments = segments;
    }

    /** {@code raw} 를 glob 으로 읽는다. 비었으면 {@link PatternProblem.Blank}, 절대 경로 glob 이 아니면 {@link PatternProblem.NotAbsolute}. */
    public static Result<PathPattern, PatternProblem> of(String raw) {
        Objects.requireNonNull(raw, "raw");
        if (raw.isBlank()) {
            return Result.err(new PatternProblem.Blank());
        }
        Scope scope;
        if (raw.startsWith(DOUBLE_STAR)) {
            scope = Scope.ANY;
        } else if (raw.startsWith(WSL_SEPARATORS)) {
            scope = Scope.WSL;
        } else if (raw.startsWith("\\\\") || isDriveAbsolute(raw)) {
            scope = Scope.WINDOWS;
        } else {
            return Result.err(new PatternProblem.NotAbsolute(raw));
        }
        String separators = scope == Scope.WSL ? WSL_SEPARATORS : ANY_SEPARATORS;
        return Result.ok(new PathPattern(raw, scope, split(raw, separators)));
    }

    /** 원문. */
    public String source() {
        return source;
    }

    /** {@code path} 가 이 glob 에 맞는가. 적용 환경이 아니면 맞지 않는다. */
    public boolean matches(WorkspacePath path) {
        Objects.requireNonNull(path, "path");
        boolean ignoreCase = switch (path.env()) {
            case ShellEnvironment.Windows _ -> true;
            case ShellEnvironment.Wsl _ -> false;
        };
        boolean applies = switch (scope) {
            case ANY -> true;
            case WSL -> !ignoreCase;
            case WINDOWS -> ignoreCase;
        };
        return applies && matchesSegments(fullSegments(path), ignoreCase);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PathPattern other && source.equals(other.source);
    }

    @Override
    public int hashCode() {
        return source.hashCode();
    }

    @Override
    public String toString() {
        return "PathPattern[" + source + "]";
    }

    // Windows 는 루트도 세그먼트로 맞춘다: 드라이브 "C:", UNC 는 호스트 · 공유 두 개
    private static List<String> fullSegments(WorkspacePath path) {
        return switch (path.env()) {
            case ShellEnvironment.Wsl _ -> path.segments();
            case ShellEnvironment.Windows _ -> {
                List<String> out = new ArrayList<>(split(path.root(), ANY_SEPARATORS));
                out.addAll(path.segments());
                yield out;
            }
        };
    }

    // matched[i][j] = 패턴 i.. 가 세그먼트 j.. 와 맞는가. "**" 가 여럿이어도 O(패턴 × 경로 깊이)
    private boolean matchesSegments(List<String> names, boolean ignoreCase) {
        int p = segments.size();
        int n = names.size();
        boolean[][] matched = new boolean[p + 1][n + 1];
        matched[p][n] = true;
        for (int i = p - 1; i >= 0; i--) {
            String glob = segments.get(i);
            for (int j = n; j >= 0; j--) {
                if (glob.equals(DOUBLE_STAR)) {
                    matched[i][j] = matched[i + 1][j] || (j < n && matched[i][j + 1]);
                } else {
                    matched[i][j] = j < n && matched[i + 1][j + 1] && matchesName(glob, names.get(j), ignoreCase);
                }
            }
        }
        return matched[0][0];
    }

    // 세그먼트 하나: '*' 는 0자 이상, '?' 는 한 글자. 마지막 '*' 로 되돌아가는 탐욕 비교
    private static boolean matchesName(String glob, String name, boolean ignoreCase) {
        String g = ignoreCase ? glob.toLowerCase(Locale.ROOT) : glob;
        String s = ignoreCase ? name.toLowerCase(Locale.ROOT) : name;
        int gi = 0;
        int si = 0;
        int star = -1;
        int resume = 0;
        while (si < s.length()) {
            if (gi < g.length() && (g.charAt(gi) == '?' || g.charAt(gi) == s.charAt(si))) {
                gi++;
                si++;
            } else if (gi < g.length() && g.charAt(gi) == '*') {
                star = gi++;
                resume = si;
            } else if (star >= 0) {
                gi = star + 1;
                si = ++resume;
            } else {
                return false;
            }
        }
        while (gi < g.length() && g.charAt(gi) == '*') {
            gi++;
        }
        return gi == g.length();
    }

    private static boolean isDriveAbsolute(String s) {
        char c = s.length() >= 3 ? s.charAt(0) : ' ';
        boolean letter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
        return letter && s.charAt(1) == ':' && (s.charAt(2) == '\\' || s.charAt(2) == '/');
    }

    // 빈 세그먼트는 버린다
    private static List<String> split(String s, String separators) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (separators.indexOf(c) >= 0) {
                if (!current.isEmpty()) {
                    out.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            out.add(current.toString());
        }
        return List.copyOf(out);
    }

    /** 적용 환경. */
    private enum Scope {
        WSL,
        WINDOWS,
        ANY
    }
}
