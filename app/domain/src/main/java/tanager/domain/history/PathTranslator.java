package tanager.domain.history;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * 한 환경의 작업 경로를 다른 환경의 셸 cwd 로 바꾼다(순수 함수, #90, SDD §10.3). "같은 폴더를 다른 셸로 열기"(FR-HIS-06)에 쓴다. 바꿀 수
 * 없으면 empty — 리눅스 쪽 경로(WSL 홈 등)는 Windows 셸의 cwd 로 쓰지 않는다.
 */
public final class PathTranslator {
    private static final List<String> WSL_UNC_HOSTS = List.of("wsl.localhost", "wsl$");

    private PathTranslator() {}

    /** {@code from} 을 {@code to} 환경의 경로로. 같은 환경이면 그대로다. */
    public static Optional<WorkspacePath> translate(WorkspacePath from, ShellEnvironment to, WslMount mount) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(mount, "mount");
        if (from.env().equals(to)) {
            return Optional.of(from);
        }
        Optional<String> raw = switch (from.env()) {
            case ShellEnvironment.Wsl _ ->
                switch (to) {
                    case ShellEnvironment.Windows _ -> wslToWindows(from, mount);
                    // 드라이브는 모든 배포판이 같은 자리에 붙인다고 본다. 리눅스 쪽 경로는 배포판마다 다르다
                    case ShellEnvironment.Wsl _ -> wslToWindows(from, mount).map(_ -> from.path());
                };
            case ShellEnvironment.Windows _ ->
                switch (to) {
                    case ShellEnvironment.Wsl(String distro) -> windowsToWsl(from, distro, mount);
                    case ShellEnvironment.Windows _ -> Optional.of(from.path());
                };
        };
        return raw.flatMap(path -> WorkspacePath.of(to, path).value());
    }

    // /mnt/c/a/b → C:\a\b. 마운트 루트 바로 아래가 드라이브 문자 한 글자일 때만
    private static Optional<String> wslToWindows(WorkspacePath from, WslMount mount) {
        List<String> segments = from.segments();
        List<String> prefix = mount.segments();
        if (segments.size() <= prefix.size()
                || !segments.subList(0, prefix.size()).equals(prefix)) {
            return Optional.empty();
        }
        String drive = segments.get(prefix.size());
        if (!isDriveLetter(drive)) {
            return Optional.empty();
        }
        List<String> rest = segments.subList(prefix.size() + 1, segments.size());
        return Optional.of(drive.toUpperCase(Locale.ROOT) + ":\\" + String.join("\\", rest));
    }

    // C:\a → /mnt/c/a, \\wsl.localhost\U\a → /a (그 배포판만)
    private static Optional<String> windowsToWsl(WorkspacePath from, String distro, WslMount mount) {
        String root = from.root();
        String tail = String.join("/", from.segments());
        if (!root.startsWith("\\\\")) {
            String drive = root.substring(0, 1).toLowerCase(Locale.ROOT);
            return Optional.of(mount.root() + drive + (tail.isEmpty() ? "" : "/" + tail));
        }
        String[] unc = root.substring(2).split("\\\\");
        boolean wslHost = WSL_UNC_HOSTS.contains(unc[0].toLowerCase(Locale.ROOT));
        // UNC 는 대소문자를 가리지 않는다
        if (!wslHost || !unc[1].equalsIgnoreCase(distro)) {
            return Optional.empty();
        }
        return Optional.of("/" + tail);
    }

    private static boolean isDriveLetter(String segment) {
        return segment.length() == 1 && Character.isLetter(segment.charAt(0)) && segment.charAt(0) < 128;
    }
}
