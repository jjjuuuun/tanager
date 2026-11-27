package tanager.domain.session;

/** 셸이 현재 디렉터리를 알리는 방식(ADR-0003). cwd 추적기가 어떤 OSC 를 읽을지 정한다. */
public enum IntegrationMode {
    /** bash · zsh 의 OSC 7 ({@code file://host/path}). */
    OSC7,
    /** PowerShell · cmd 의 OSC 9;9 (따옴표 안의 Windows 경로). */
    OSC9_9,
    /** 셸 통합 없음 — cwd 를 추적하지 않는다. */
    NONE
}
