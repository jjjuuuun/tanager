package dev.tanager.application.port.out;

import dev.tanager.application.platform.OperatingSystem;
import dev.tanager.domain.session.ShellProfile;
import java.util.List;

/**
 * OS 별 차이(셸 탐지 · 경로 변환 · 셸 통합 · 리소스 · 외관)를 숨기는 출력 포트(ADR-0002).
 * 구현은 tanager-platform-&lt;os&gt; 모듈에 있고 META-INF/services 로 등록한다. 기능은 마일스톤마다 늘어난다.
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
     * 블로킹될 수 있어 EDT 에서 부르지 않는다. 순서에 의미가 있다 — 첫 항목이 기본 프로파일이다. 돌려주는 목록은 수정할 수
     * 없다.
     */
    List<ShellProfile> shells();
}
