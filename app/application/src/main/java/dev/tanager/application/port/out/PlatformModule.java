package dev.tanager.application.port.out;

import dev.tanager.application.platform.OperatingSystem;
import dev.tanager.domain.session.ShellProfile;
import dev.tanager.domain.session.WorkingDir;
import java.util.List;

/**
 * OS 별 차이(셸 탐지 · 경로 변환 · 셸 통합 · 리소스 · 외관)를 숨기는 출력 포트(ADR-0002).
 * 구현은 app/platform/&lt;os&gt; 모듈에 있고 META-INF/services 로 등록한다. 기능은 마일스톤마다 늘어난다.
 */
public interface PlatformModule {

    /** 짧은 식별자. 예: "windows", "macos". */
    String id();

    /** 이 구현이 주어진 OS 에서 동작하는가. */
    boolean supports(OperatingSystem os);

    /**
     * 이 OS 에서 띄울 수 있는 셸 프로파일을 탐지한다.
     *
     * <p>예외를 던지지 않는다. 일부 탐지가 실패하면(WSL 미설치 등) 그 항목만 빠진다. 외부 명령을 실행하므로 최대 수 초
     * 블로킹될 수 있어 EDT 에서 부르지 않는다. 순서는 탐지 순서(Windows 는 WSL 배포판 → PowerShell → cmd)이고, 기본
     * 프로파일 결정은 ListProfiles · 설정(FR-SES-02)이 한다. 돌려주는 목록은 수정할 수 없다.
     */
    List<ShellProfile> shells();

    /**
     * {@code profile} 셸을 {@code dir} 에서 띄우는 PTY 명령을 만든다(ADR-0007 · 0009). 외부 상태를 보지 않는 순수 계산이다 —
     * 경로가 실제로 있는지는 확인하지 않는다. {@link WorkingDir.Home} 을 그 환경의 홈으로 바꾸는 일도 여기서 한다.
     *
     * <p>{@code integration} 은 셸 통합 설치 결과다(ADR-0014). {@link Integration.Installed} 면 셸에 통합 스크립트를 주입하고,
     * {@link Integration.Unavailable} 이면 통합 없이 띄우는 명령을 만든다.
     *
     * @throws IllegalArgumentException {@code dir} 이 경로이고 그 환경이 {@code profile} 의 환경과 다를 때, 또는 만든 argv 를 OS
     *     명령줄로 옮기면 인자가 갈라질 때(#107 — {@code WorkspacePath} 가 먼저 막으므로 둘 다 호출자의 버그다)
     */
    LaunchCommand launchCommand(ShellProfile profile, WorkingDir dir, Integration integration);

    /** 앱 데이터 폴더 등 OS 별 위치. 계산만 하고 디렉터리를 만들지 않는다. */
    PlatformDirs dirs();

    /** 이 OS 의 셸 통합 스크립트 설치기(ADR-0014). 돌려주는 것만으로는 아무것도 쓰지 않는다 — {@code install()} 이 쓴다. */
    ShellIntegration integration();
}
