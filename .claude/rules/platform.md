---
paths:
  - "app/platform/*/**"
  - "app/adapter/pty/**"
---
# 플랫폼·PTY 주의사항

- `wsl.exe --list --quiet` 출력은 **UTF-16LE** 이다. 환경변수 `WSL_UTF8=1` 을 주거나 UTF-16LE 로 디코딩한다.
- WSL 세션 기동: `wsl.exe -d <배포판> --cd <경로|~> -- [ -r "$TANAGER_RC" ] && exec bash --rcfile "$TANAGER_RC" -i || exec bash -i` (ADR-0014). `--` 뒤는 기본 로그인 셸(`/etc/passwd`)의 `-c` 가 해석하고, 원소마다 공백이 없어야 한다. rcfile 을 못 읽으면 M2 와 같은 `bash -i` 로 물러선다. 통합 스크립트는 Windows 쪽 `%APPDATA%\Tanager\shell-integration\` 에 풀고, 환경 `TANAGER_RC=<그 폴더>\tanager.bash`(Windows 경로) + `WSLENV=<기존>:TANAGER_RC/p` 로 넘긴다 — `/mnt/<드라이브>/` 를 직접 계산하지 않는다(automount 루트가 바뀐 배포판에서 틀림).
  - `"$TANAGER_RC"` 는 argv 원소에 큰따옴표까지 넣는다. `--` 뒤는 리눅스 기본 셸이 펼치므로 따옴표가 없으면 공백 경로(사용자 이름)에서 갈린다.
  - `--cd` 경로 주입 방지(#107, ADR-0014): wsl.exe(2.5.10 실측)는 따옴표 토큰을 다음 `"` 에서 끝내고 `\"` · `""` 이스케이프가 없다. pty4j `joinCmdArgs` 는 공백 원소를 감싸기만 한다 — 이스케이프로는 풀 수 없다. 그래서 `WorkspacePath` 가 출처에서 `"` · C0 제어 문자 · DEL(WSL 은 `\` 도)을 거부하고, `WindowsLaunchCommands.requireSafeArgv` 가 argv 에서 한 번 더(감쌀 원소의 `"` · 끝 `\`, WSL `--` 앞 원소의 모든 `"`) 막는다. pty4j 를 올려도 이 검사를 지우지 않는다.
  - 기존 `WSLENV` 는 지우지 않고 보존한다(빈 항목 · 기존 `TANAGER_RC` 항목만 정리).
  - `--rcfile` 은 `-l` 과 못 쓰므로 `tanager.bash` 가 로그인 셸 순서를 흉내 낸다. `/etc/bash.bashrc` 가 있는 배포판에서만 `/etc/profile` 을 읽는 동안 `PS1` 을 비워 이중 실행을 막고(Fedora 계열 `/etc/bashrc` 는 건드리지 않음), 그 때문에 빠진 bash-completion 은 다시 불러온다.
- 위 `--cd` 검사는 wsl.exe 인자 분리만 막는다. cmd `/c` · `/k` · PowerShell `-Command` 문자열에 사용자 경로를 넣지 않는다(`lpCurrentDirectory` · 환경 변수로 넘긴다). 불가피하면 셸별 이스케이프와 테스트를 둔다(ADR-0014 #107).
- WSL 리소스 수집은 **실행 중인 WSL 세션이 있을 때만** `wsl.exe -d <배포판> -- sh -c 'cat /proc/meminfo; df -kP /'` 로, 5초 이상 간격. 세션이 없을 때 `wsl.exe` 를 부르면 VM 이 깨어난다.
- 다크/라이트: `HKCU\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize\AppsUseLightTheme`(0 = 다크). 애니메이션 끄기: `SystemParametersInfo(SPI_GETCLIENTAREAANIMATION)`.
- 지원 셸: WSL · **Windows PowerShell 5.1** · cmd. PowerShell 7(`pwsh`)은 범위 밖(사용자 결정) — 탐지·기동·셸 통합·테스트 대상 아님. 사용자 정의 프로파일도 없으므로 Tanager 가 띄우는 셸은 자동 탐지한 이 셋뿐이다.
- bash OSC 7 퍼센트 인코딩 함수 첫 줄에 `local LC_ALL=C` 필수 — 없으면 UTF-8 로캘에서 한글이 코드 포인트(`%D55C`)로 나간다(M0 에서 재현).
- ConPTY: pty4j 0.14 번들 `conpty.dll` 사용(`setUseWinConPty(true)` → `WinConPtyProcess`). cmd 의 `mode con` 은 ConPTY 아래에서 출력이 없다 — 크기 확인은 하위 프로세스로.
- PowerShell 기동: `powershell.exe -NoLogo -NoExit -Command ". '<tanager.ps1>'"`. 5.1 문법만 쓴다(`` `e `` 없음 → `[char]27`, `??`·삼항 연산자 금지). 스크립트 파일은 UTF-8 **BOM 포함**이어야 5.1 이 한글을 깨지 않고 읽는다.
- cwd 추적은 셸 프롬프트가 내보내는 OSC 시퀀스로 한다: bash/zsh → `OSC 7 ; file://<host><퍼센트 인코딩 경로> ST`, PowerShell → `OSC 9 ; 9 ; "<Windows 경로>" ST`. 시퀀스는 read 경계에서 잘릴 수 있으므로 스캐너는 상태를 가진 스트리밍 파서여야 한다.
- 셸 통합 스크립트(`*.sh`)는 LF, `*.ps1` 은 CRLF (`.gitattributes`). 한글 경로는 UTF-8 로 퍼센트 인코딩.
- 경로 비교: Windows 는 대소문자 무시, WSL·macOS 는 구분. `WorkspacePath` 가 환경별 정책을 가진다.
- ConPTY 는 Windows 10 1809+ 필요. pty4j 의 ConPTY 사용 여부는 M0 스파이크에서 확인한 값(ADR-0001)을 따른다.
- 통합 테스트는 `@Tag("windows")` / `@Tag("wsl")` 를 붙이고, 각 OS 가 아니면 `@EnabledOnOs` 로 건너뛴다.
