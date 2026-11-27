package tanager.domain.session;

/** 자동 탐지 대상 셸 종류. 사용자 정의 셸은 두지 않는다(SDD §5.4). PowerShell 7 은 범위 밖. */
public enum ShellKind {
    BASH,
    ZSH,
    WINDOWS_POWERSHELL,
    CMD
}
