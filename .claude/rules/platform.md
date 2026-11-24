---
paths:
  - "tanager-platform-*/**"
  - "tanager-adapter-pty/**"
---
# 플랫폼·PTY 주의사항

- `wsl.exe --list --quiet` 출력은 **UTF-16LE** 이다. 환경변수 `WSL_UTF8=1` 을 주거나 UTF-16LE 로 디코딩한다.
- WSL 세션 기동: `wsl.exe -d <배포판> --cd <경로> -- bash --rcfile <통합 스크립트> -i`. 통합 스크립트는 Windows 쪽 `%APPDATA%\Tanager\shell-integration\` 에 풀고 `/mnt/<드라이브>/...` 경로로 넘긴다.
- WSL 리소스 수집은 **실행 중인 WSL 세션이 있을 때만** `wsl.exe -d <배포판> -- sh -c 'cat /proc/meminfo; df -kP /'` 로, 5초 이상 간격. 세션이 없을 때 `wsl.exe` 를 부르면 VM 이 깨어난다.
- 다크/라이트: `HKCU\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize\AppsUseLightTheme`(0 = 다크). 애니메이션 끄기: `SystemParametersInfo(SPI_GETCLIENTAREAANIMATION)`.
- PowerShell 기동: `pwsh.exe -NoLogo -NoExit -Command ". '<tanager.ps1>'"` (5.1 은 `powershell.exe`, `` `e `` 대신 `[char]27`).
- cwd 추적은 셸 프롬프트가 내보내는 OSC 시퀀스로 한다: bash/zsh → `OSC 7 ; file://<host><퍼센트 인코딩 경로> ST`, PowerShell → `OSC 9 ; 9 ; "<Windows 경로>" ST`. 시퀀스는 read 경계에서 잘릴 수 있으므로 스캐너는 상태를 가진 스트리밍 파서여야 한다.
- 셸 통합 스크립트(`*.sh`)는 LF, `*.ps1` 은 CRLF (`.gitattributes`). 한글 경로는 UTF-8 로 퍼센트 인코딩.
- 경로 비교: Windows 는 대소문자 무시, WSL·macOS 는 구분. `WorkspacePath` 가 환경별 정책을 가진다.
- ConPTY 는 Windows 10 1809+ 필요. pty4j 의 ConPTY 사용 여부는 M0 스파이크에서 확인한 값(ADR-0001)을 따른다.
- 통합 테스트는 `@Tag("windows")` / `@Tag("wsl")` 를 붙이고, 각 OS 가 아니면 `@EnabledOnOs` 로 건너뛴다.
