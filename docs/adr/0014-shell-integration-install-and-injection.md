# 0014. 셸 통합 스크립트 배포와 주입

- 상태: 채택(#80, 2026-11-26) — 배포는 #80 에서 구현했다. 주입(WSL · PowerShell · cmd)은 #55 · #81 에서 구현한다.
- 날짜: 2026-11-26
- 관련: #80 · #55 · #81, FR-SES-03, SDD §9.1 · §10.2, ADR-0003 · 0007 · 0009(개정)

## 맥락
ADR-0003 은 셸 통합 스크립트가 프롬프트마다 OSC 7(bash) · OSC 9;9(PowerShell · cmd)로 cwd 를 알리게 했다. M0 스크립트는 SDD §10.2 에만 있었다. M3 에서 실제로 배포 · 주입하려면 다음을 정해야 한다.

1. 스크립트를 어디에 두고, 언제 · 어떻게 앱 데이터로 풀지. 줄바꿈 · BOM 이 틀리면 bash 는 `$'\r'` 로 죽고, PowerShell 5.1 은 한글을 깨뜨린다.
2. 설치 결과를 기동 명령 계산(순수 함수, ADR-0007 · 0009)에 어떻게 넘길지.
3. WSL 에서 rcfile 경로를 어떻게 넘길지. `--rcfile` 을 쓰면 `-l` 을 같이 쓸 수 없어 로그인 셸 설정(`~/.profile`)이 읽히지 않는다(#55).
4. 사용자의 프롬프트 훅(starship · oh-my-posh · 사용자 `PROMPT`)을 지우거나 `$?` 를 망가뜨리지 않는 법(위험 M3-R2).

## 선택지
**배포 위치**: (a) application 이나 bootstrap 리소스에 둔다. (b) **OS 마다 셸 · 문법이 다르므로 `app/platform/<os>` 리소스에 둔다.**

**갱신 판단**: (a) 버전 파일을 두고 버전이 다를 때만 쓴다. (b) **파일마다 바이트를 비교해 다를 때만 쓴다.**

**WSL rcfile 경로**: (a) `/mnt/<d>/…` 를 직접 계산한다(M0 에서 확인한 방식). automount 루트가 바뀐 배포판에서 틀린다. (b) `wslpath` 로 조회한다. VM 을 깨워 첫 창이 늦어진다. (c) **`WSLENV=…:TANAGER_RC/p` 로 Windows 경로를 넘겨 WSL 이 변환하게 한다.**

## 결정
### 배포 (#80 에서 구현)
- 스크립트는 `app/platform/windows/src/main/resources/shell-integration/` 에 둔다. macOS 는 M7 에 zsh 판을 따로 둔다.
  - `tanager.bash`: LF, BOM 없음. `.gitattributes` 의 `*.bash text eol=lf`.
  - `tanager.ps1`: UTF-8 BOM, CRLF. `.gitattributes` 의 `*.ps1 text eol=crlf`. `core.autocrlf=false` 라서 작업 트리 파일이 CRLF 여야 Gradle 이 CRLF 로 패키징한다.
  - 바이트 계약은 `ShellIntegrationResourcesTest` 가 검사한다.
- port.out `ShellIntegration { Integration install(); }` 와 `Integration = Installed(directory) | Unavailable(reason)`, `PlatformModule.integration()` 을 둔다.
- `WindowsShellIntegration.install()` 은 `<appData>\shell-integration\` 에 쓴다.
  - 디렉터리를 만든다.
  - 파일마다 기존 바이트와 비교해 같으면 건드리지 않는다(수정 시각 유지).
  - 다르면 같은 디렉터리의 임시 파일에 쓰고 `ATOMIC_MOVE` + `REPLACE_EXISTING` 으로 옮긴다. 실패하면 임시 파일을 지운다.
  - 예외를 던지지 않는다. `IOException` · `RuntimeException` 은 `Unavailable(이유)` 가 된다.
- 버전 파일은 두지 않는다. 바이트 비교는 사용자가 고친 파일까지 되돌린다.
- macOS 의 `integration()` 은 M7 전까지 `Unavailable("M7")` 이다.
- bootstrap 은 시작할 때 가상 스레드에서 `install()` 을 한 번 부르고 결과를 로그에 남긴다(`Unavailable` 은 WARNING). 셸 기동은 기다리지 않는다.

### 기동 명령에 넘기기 (#55 · #81 에서 구현)
- 설치 결과를 `launchCommand(profile, dir, integration)` 의 세 번째 인자로 넘긴다(ADR-0007 · 0009 개정). 기동 명령 계산은 계속 순수 함수다.
- `SessionStarter` 는 셸 탐지와 같은 방식으로 설치 결과를 기다린다.

### WSL (#55 에서 구현)
- 환경 `TANAGER_RC=<Windows 경로>\tanager.bash` 와 `WSLENV=<기존>:TANAGER_RC/p`(기존이 없으면 `TANAGER_RC/p`)를 준다. argv 는 `wsl.exe -d <d> --cd <경로> -- bash --rcfile "$TANAGER_RC" -i` 다. `wsl.exe` 는 `--` 뒤 명령을 기본 셸로 실행하므로 변수가 펼쳐진다.
- 기존 `WSLENV` 는 모듈을 만들 때 환경에서 한 번 읽는다(순수 계산 유지).
- 이 방식이 #55 의 wsl 통합 테스트에서 실패하면 (a) `/mnt/` 직접 계산으로 물러서고, 결과를 이 ADR 에 적는다.
- `Unavailable` 이면 M2 명령 그대로 띄운다.

### #55 로그인 셸 설정
- `tanager.bash` 가 bash 로그인 셸 순서를 흉내 낸다: `/etc/profile` → `~/.bash_profile` | `~/.bash_login` | `~/.profile` 중 처음 있는 것 → 셋 다 없으면 `~/.bashrc`. 그냥 `wsl.exe` 를 띄울 때(로그인 셸)와 PATH 가 같다. VS Code 셸 통합과 같은 방식이다.
- 스크립트 내용은 #80 에 넣었다. 동작 검증(@Tag wsl)과 ADR-0003 개정 절은 #55 에서 한다.

### 훅 공존 (스크립트는 #80, 검증은 #55 · #81)
- bash
  - `PROMPT_COMMAND` 가 배열(bash 5.1+)이면 원소를 더하고, 문자열이면 앞에 붙인다.
  - 훅은 받은 `$?` 를 그대로 돌려준다. 뒤의 훅과 `PS1` 이 직전 명령의 상태를 본다.
  - 이미 들어 있으면 두 번 넣지 않는다.
  - 퍼센트 인코딩 함수 첫 줄은 `local LC_ALL=C` 다. 없으면 한글이 코드 포인트(`%D55C`)로 나간다(M0 재현).
- PowerShell 5.1
  - 원래 prompt 를 감싼다. 이미 감쌌으면 다시 감싸지 않는다.
  - 원래 prompt 를 부르기 전에 `$?` · `$LASTEXITCODE` 를 되돌려, 원래 prompt 가 직전 명령의 값을 보게 한다. `$?` 는 직접 대입할 수 없어, 실패였으면 `Write-Error -ErrorAction Ignore` 로 실패를 한 번 일으킨다.
  - FileSystem 공급자일 때만 `ESC]9;9;"<ProviderPath>"ESC\` 를 앞에 붙인다.
  - 5.1 문법만 쓴다(`` `e `` · `??` · 삼항 없음).
  - 기동은 `powershell.exe -NoLogo -NoExit -ExecutionPolicy Bypass -Command ". '<경로>'"` 다(경로의 `'` 는 `''`). 그룹 정책이 막으면 통합이 없다(위험 R4). 기동 명령은 #81 에서 구현한다.
- cmd: 환경 `PROMPT=$e]9;9;$P$e\` + (상속한 `PROMPT` 또는 `$P$G`)를 준다. 상속값은 모듈을 만들 때 한 번 읽는다. #81 에서 구현한다.
- 환경 병합: `LaunchCommand` 의 대소문자 무시 병합 규칙(#54)을 쓴다. 어댑터에는 OS 분기가 없다.

## 결과
- 스크립트 바이트(BOM · 줄바꿈 · `local LC_ALL=C`)가 단위 테스트로 고정된다. 설치기는 `@TempDir` 로 OS 와 상관없이 검사한다.
- 내용이 같으면 재시작해도 파일을 다시 쓰지 않는다. 사용자가 스크립트를 고치면 다음 시작에 되돌려진다.
- 설치에 실패하거나 `IntegrationMode.NONE` 이면 시작 경로만 기록하고 머리줄에 알린다(ADR-0003 결과 그대로, #55 · #81 이후).
- #80 에서 확인한 것(2026-11-26)
  - `bash -n tanager.bash` 통과(bash 5.2.21). 임시 HOME 에서 `bash --rcfile tanager.bash -i` 로 띄워 한글 · 공백 · `#` 경로가 UTF-8 바이트로 퍼센트 인코딩되고, 문자열 · 배열 `PROMPT_COMMAND` 모두 사용자 훅이 `false` 뒤 `$?=1` 을 보며, 다시 source 해도 OSC 가 한 번만 나오는 것을 봤다.
  - PowerShell 5.1.26100 의 `Parser::ParseFile` 오류 0건. 두 번 dot-source 해도 OSC 9;9 가 한 번만 나오고, 원래 prompt 가 `cmd /c exit 3` 뒤 `$?=False` · `$LASTEXITCODE=3` 을 보며, 레지스트리 공급자(`HKCU:\`)에서는 OSC 를 내지 않는 것을 봤다.
