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

    private WindowsHostPaths() {}

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
