package tanager.application.port.out;

import java.nio.file.Path;
import java.util.Optional;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.shared.Result;

/**
 * 환경의 파일을 이 앱이 읽을 경로와 그 환경의 홈(FR-TK-04 · NFR-07, #205, SDD §10.3). 도구함 · Claude 대화 읽기가 쓴다. {@link
 * PlatformModule#hostFiles()} 가 준다.
 *
 * <p>{@link PathTranslation} 은 <b>셸 cwd</b> 로 바꾸는 변환이라 리눅스 쪽 WSL 경로에 empty 를 돌려준다. 이 포트는 앱(JVM)이 {@code
 * java.nio} 로 여는 경로를 준다.
 */
public interface HostFiles {

    /**
     * 이 앱이 {@code java.nio} 로 읽을 수 있는 경로. 순수 계산이다 — 파일시스템을 보지 않고 VM 을 깨우지 않는다. 예외를 던지지 않는다.
     *
     * <p>Windows 경로는 그대로. WSL 의 드라이브 마운트 아래({@code /mnt/c/…})는 드라이브 경로({@code C:\…} — 9P 를 거치지 않아 빠르다,
     * NFR-07). 그 밖의 WSL 경로는 {@code \\wsl.localhost\<배포판>\…}. 이 앱이 열 수 없는 이름이면 empty.
     */
    Optional<Path> hostPath(WorkspacePath path);

    /**
     * 그 환경의 사용자 홈(그 환경의 경로). 예외를 던지지 않는다.
     *
     * <p>블로킹이다 — EDT · {@code StateActor} 에서 부르지 않는다. WSL 은 처음 한 번 배포판에 물어 VM 을 깨운다. 그래서 부를지는 부르는 쪽이
     * 정한다(도구함은 WSL 폴더를 고를 때만, 대화 가져오기는 그 배포판에 세션이 있을 때만). 성공한 값은 앱이 끝날 때까지 기억하고, 실패는 기억하지
     * 않는다(다음 호출이 다시 묻는다).
     */
    Result<WorkspacePath, HomeError> home(ShellEnvironment env);
}
