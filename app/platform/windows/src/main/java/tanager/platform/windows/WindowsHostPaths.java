package tanager.platform.windows;

import java.util.Optional;
import tanager.domain.history.PathTranslator;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.history.WslMounts;

/**
 * 환경의 경로를 이 앱(Windows JVM)이 여는 Windows 파일 경로로 바꾼다(순수 함수, #53). 디렉터리 확인과 호스트 경로가 같이 쓴다. 파일시스템을 보지
 * 않는다.
 */
final class WindowsHostPaths {
    private static final ShellEnvironment WINDOWS = new ShellEnvironment.Windows();
    private static final String WSL_HOST = "\\\\wsl.localhost\\";

    // Windows 파일 이름에 쓸 수 없는 글자(" · \ · 제어 문자는 WorkspacePath 가 WSL 경로에서 이미 막는다)
    private static final String WIN32_RESERVED = "<>:|?*";

    private WindowsHostPaths() {}

    /**
     * 이 앱이 여는 경로(#205). Windows 경로는 그대로, WSL 의 드라이브 마운트 아래는 {@code C:\…}, 그 밖의 WSL 경로는 {@code
     * \\wsl.localhost\<배포판>\…}. Windows 이름으로 옮길 수 없는 WSL 경로는 empty 다 — Win32 는 끝 점 · 공백을 지워 다른 파일을 가리키고, 예약
     * 글자가 든 이름은 열지 못한다.
     */
    static Optional<String> hostPath(WorkspacePath path, WslMounts mounts) {
        return switch (path.env()) {
            case ShellEnvironment.Windows _ -> Optional.of(path.path());
            case ShellEnvironment.Wsl(String distro) -> {
                if (!path.segments().stream().allMatch(WindowsHostPaths::isWindowsName)) {
                    yield Optional.empty();
                }
                yield onDrive(path, mounts).or(() -> Optional.of(wslUnc(distro, path)));
            }
        };
    }

    // 조각은 비어 있지 않다(WorkspacePath 가 빈 조각을 버린다)
    private static boolean isWindowsName(String segment) {
        char last = segment.charAt(segment.length() - 1);
        return last != '.' && last != ' ' && segment.chars().noneMatch(c -> WIN32_RESERVED.indexOf(c) >= 0);
    }

    /** WSL 의 드라이브 마운트 아래({@code /mnt/c/…})면 드라이브 경로({@code C:\…}). 그 밖이면 empty. 루트는 배포판마다 {@code mounts} 에서. */
    static Optional<String> onDrive(WorkspacePath path, WslMounts mounts) {
        return PathTranslator.translate(path, WINDOWS, mounts).map(WorkspacePath::path);
    }

    /** 배포판의 리눅스 경로를 {@code \\wsl.localhost\<배포판>\…} 로. 배포판(VM)을 거쳐 읽는 경로다. */
    static String wslUnc(String distro, WorkspacePath path) {
        return wslRoot(distro) + "\\" + String.join("\\", path.segments());
    }

    /** 배포판 파일시스템의 루트 {@code \\wsl.localhost\<배포판>}(끝 구분자 없음). */
    static String wslRoot(String distro) {
        return WSL_HOST + distro;
    }
}
