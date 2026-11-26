package dev.tanager.application.port.out;

/** 셸 통합 스크립트를 셸이 읽을 수 있는 곳에 배포하는 출력 포트(ADR-0003 · 0014). 구현은 플랫폼 모듈에 있다. */
public interface ShellIntegration {

    /**
     * 스크립트를 설치한다. 내용이 이미 같으면 파일을 건드리지 않는다.
     *
     * <p>예외를 던지지 않는다 — 실패는 {@link Integration.Unavailable} 로 돌려준다. 파일 I/O 를 하므로 EDT 에서 부르지 않는다.
     */
    Integration install();
}
