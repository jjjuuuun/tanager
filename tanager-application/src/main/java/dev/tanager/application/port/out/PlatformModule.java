package dev.tanager.application.port.out;

import dev.tanager.application.platform.OperatingSystem;

/**
 * OS 별 차이(셸 탐지 · 경로 변환 · 셸 통합 · 리소스 · 외관)를 숨기는 출력 포트(ADR-0002).
 * 구현은 tanager-platform-&lt;os&gt; 모듈에 있고 META-INF/services 로 등록한다. 기능은 마일스톤마다 늘어난다.
 */
public interface PlatformModule {

    /** 짧은 식별자. 예: "windows", "macos". */
    String id();

    /** 이 구현이 주어진 OS 에서 동작하는가. */
    boolean supports(OperatingSystem os);
}
