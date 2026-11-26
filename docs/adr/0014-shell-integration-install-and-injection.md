# 0014. 셸 통합 스크립트 배포와 주입

- 상태: 채택(#80, 2026-11-26) — 배포는 #80, WSL 주입은 #55(2026-11-27), PowerShell · cmd 주입은 #81(2026-11-27)에서 구현했다.
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
  - `tanager.ps1`: UTF-8 BOM, CRLF. `.gitattributes` 의 `*.ps1 text eol=crlf`. `.gitattributes` 의 `eol=crlf` 가 체크아웃 때 CRLF 를 강제하고, Gradle 은 바이트를 그대로 복사한다.
  - 바이트 계약은 `ShellIntegrationResourcesTest` 가 검사한다.
- port.out `ShellIntegration { Integration install(); }` 와 `Integration = Installed(directory) | Unavailable(reason)`, `PlatformModule.integration()` 을 둔다.
- `WindowsShellIntegration.install()` 은 `<appData>\shell-integration\` 에 쓴다.
  - 디렉터리를 만든다.
  - 파일마다 기존 바이트와 비교해 같으면 건드리지 않는다(수정 시각 유지).
  - 다르면 같은 디렉터리의 임시 파일에 쓰고 `ATOMIC_MOVE` + `REPLACE_EXISTING` 으로 옮긴다. 끝나면 성공 · 실패와 상관없이 임시 파일을 지운다.
  - 임시 파일 이름은 `<이름>.tmp`(`tanager.bash.tmp` · `tanager.ps1.tmp`) 하나로 고정한다. 매번 임의 이름으로 만들면 강제 종료 때마다 하나씩 쌓인다. 고정 이름이면 이전 실행이 남긴 tmp 를 다음 설치가 덮어쓴다. 예전 판이 남긴 임의 이름(`tanager.*.tmp`)도 설치를 시작할 때 지운다.
  - 교체 재시도: Windows 는 대상 파일을 다른 프로세스(이미 뜬 셸 · 백신)가 열고 있으면 교체를 `AccessDenied` 로 거부한다. 100ms 간격으로 세 번까지 다시 시도한다(최대 0.3초 — 시작을 늦추지 않을 만큼만). 교체가 실패한 뒤 대상 바이트가 이미 새 내용과 같으면(다른 인스턴스가 먼저 쓴 경우) 다시 시도하지 않고 `Installed` 로 본다.
    - 대기(`Sleeper`)와 교체(`Mover`)는 package-private 함수형 인터페이스로 주입한다. 테스트는 실제로 기다리지 않고 실패를 Fake 로 재현한다.
  - 예외를 던지지 않는다. `IOException` · `RuntimeException` 은 `Unavailable(이유)` 가 된다. 이유는 사용자에게 보일 짧은 문장(`셸 통합 스크립트를 설치하지 못했다: <경로> (<원인>)`)이고, 예외 전체는 `System.Logger` 에 남긴다.
- 버전 파일은 두지 않는다. 바이트 비교는 사용자가 고친 파일까지 되돌린다.
- macOS 의 `integration()` 은 M7 전까지 `Unavailable("M7")` 이다.
- `install()` 은 프로세스에서 한 번만 돈다. application 의 `service.IntegrationStatus` 가 `FutureTask` 로 결과를 기억하고(`ProfileCatalog` 와 같은 방식), 모두가 그 결과를 같이 쓴다. 같은 프로세스에서 설치가 겹쳐 고정 tmp 이름 · `tanager.*.tmp` 정리와 부딪히지 않게 하기 위해서다. 포트 계약을 어겨 예외가 나면 `Unavailable` 로 기억하고 다시 설치하지 않는다.
- bootstrap 은 시작할 때 가상 스레드에서 이 홀더를 한 번 데우고 결과를 로그에 남긴다(`Unavailable` 은 WARNING). 창을 띄우는 일은 기다리지 않는다.

### 기동 명령에 넘기기 (#55 에서 구현됨)
- 설치 결과를 `launchCommand(profile, dir, integration)` 의 세 번째 인자로 넘긴다(ADR-0007 · 0009 개정). 기동 명령 계산은 계속 순수 함수다.
- `SessionStarter` 는 셸 탐지와 같은 방식으로 `IntegrationStatus` 의 설치 결과를 기다린다(#77). start · restart 는 백그라운드 스레드에서 불리므로 EDT 를 막지 않는다.

### WSL (#55 에서 구현됨)
- 환경 `TANAGER_RC=<설치 폴더>\tanager.bash`(`Path.toString`, Windows 경로)와 `WSLENV=<기존>:TANAGER_RC/p`(기존이 없으면 `TANAGER_RC/p`)를 준다. `/p` 는 WSL 이 Windows 경로를 자기 마운트 경로로 바꾸게 한다 — automount 루트를 몰라도 된다.
- argv 는 `wsl.exe -d <d> --cd <경로|~> -- [ -r "$TANAGER_RC" ] && exec bash --rcfile "$TANAGER_RC" -i || exec bash -i` 다. 작업 디렉터리는 비운다(ADR-0007).
  - `wsl.exe` 는 `--` 뒤 명령을 리눅스 기본 로그인 셸(`/etc/passwd`)의 `-c` 로 실행하므로 변수와 `&&` · `||` 가 거기서 해석된다.
  - rcfile 을 읽을 수 없으면(지워짐 · 권한 · 변환 실패) 맨 bash(`--rcfile` 이 없는 파일이면 아무 설정도 없는 셸)가 아니라 M2 와 같은 `bash -i`(`~/.bashrc` 를 읽음)로 물러선다(#55 리뷰). `exec` 라 기본 셸이 중간에 남지 않는다(실측: `SHLVL=1`, 부모는 WSL Relay).
  - `--` 뒤 원소는 모두 공백이 없다(`WindowsWslInjectionTest` 가 검사).
  - argv 원소는 큰따옴표를 **포함한** `"$TANAGER_RC"` 다. 따옴표가 없으면 기본 셸이 펼친 경로를 공백에서 가른다 — Windows 사용자 이름에 공백이 있으면 rcfile 을 못 찾는다(실측: `[…/사용자]` `['x]` `[공백/tanager.bash]` 셋으로 갈림). 작은따옴표는 펼쳐지지 않는다.
  - 공백 · 탭이 없는 원소는 Windows 명령줄로 옮길 때 다시 감싸이지 않는다. JDK `ProcessBuilder` 와 pty4j 0.14 의 `joinCmdArgs`(ConPTY 도 같은 함수) 모두 그대로 넘긴다.
- 기존 `WSLENV` 는 모듈을 만들 때 환경에서 한 번 읽는다(순수 계산 유지). 병합 규칙:
  1. `:` 로 나누고 앞뒤 공백을 지운 뒤 빈 항목을 버린다.
  2. 이름(`/` 앞)이 `TANAGER_RC` 인 항목은 대소문자를 무시하고 지운다 — Tanager 안에서 Tanager 를 띄우면 상속한 WSLENV 에 이미 있다.
  3. 나머지는 순서대로 두고 맨 뒤에 `TANAGER_RC/p` 하나를 붙인다.
- `/p` 변환은 한글 · 공백 · `'` 가 든 앱 데이터 경로에서도 맞게 동작했다. 그래서 (a) `/mnt/` 직접 계산으로 물러서지 않는다 — 직접 계산해도 같은 따옴표 문제가 남는다.
- `Unavailable` 이면 M2 명령(`… -- bash -i`) 그대로 띄우고 환경 변수를 더하지 않는다. PowerShell · cmd 는 아래 "#81" 절.
- 기본 로그인 셸이 bash 가 아닌 배포판 사용자(zsh · fish 등)는 `[ … ] && … || …` 와 `"$TANAGER_RC"` 를 그 셸이 해석한다. 이 PC 는 bash 라 확인하지 않았다(미검증).

### #55 로그인 셸 설정
- `tanager.bash` 가 bash 로그인 셸 순서를 흉내 낸다: `/etc/profile` → `~/.bash_profile` | `~/.bash_login` | `~/.profile` 중 처음 있는 것 → 셋 다 없으면 `~/.bashrc`. 그냥 `wsl.exe` 를 띄울 때(로그인 셸)와 PATH 가 같다. VS Code 셸 통합과 같은 방식이다.
- Debian 계열 `/etc/bash.bashrc` 이중 실행을 막으려고 `/etc/profile` 을 source 하는 동안만 `PS1` 을 비운다(아래 "#55 에서 확인한 것" 의 회피책 (a)).
  - `/etc/bash.bashrc` 가 있을 때(Debian · Ubuntu · Arch)만 비운다. Fedora 계열의 `/etc/bashrc` 는 `PS1` 을 보고 대화형 설정을 정하고 `BASHRCSOURCED` 를 남기므로, 비우면 그 블록을 건너뛴 채 다시 읽히지 않는다(#55 리뷰).
  - 끝난 뒤 `PS1` 이 비어 있을 때만 원래 값으로 되돌린다. 원래 unset 이었으면 unset 으로 되돌린다. `/etc/profile` 이 `PS1` 을 새로 정했으면 그 값을 둔다(Debian 은 정하지 않으므로 동작이 같다).
  - 비운 `PS1` 때문에 `/etc/profile.d/bash_completion.sh` 도 completion 을 불러오지 않는다(`PS1` 이 있을 때만 불러온다). 그래서 되돌린 뒤 `BASH_COMPLETION_VERSINFO` 가 없고 그 파일을 읽을 수 있으면 직접 source 한다. 로그인 셸과 같아진다.
- 스크립트 내용은 #80 에 넣었고, 동작 검증(@Tag wsl)은 #55 에서 했다. ADR-0003 에 개정 절을 남겼다.
- Fedora 계열은 이 흉내와 별개로 `/etc/profile.d/*.sh` 를 두 번 읽을 수 있다. 우리 셸은 로그인 셸이 아니므로, `/etc/profile` 이 한 번 읽은 뒤 `~/.bashrc` → `/etc/bashrc` 가 "로그인 셸이 아니면 profile.d 를 읽는다" 분기로 다시 읽는다. 대부분 두 번 돌아도 무해하다(미검증, 이 PC 에 Fedora 없음).

### 훅 공존 (스크립트는 #80, 검증은 #55 · #81)
- bash
  - `PROMPT_COMMAND` 가 배열(bash 5.1+)이면 원소를 더하고, 문자열이면 앞에 붙인다.
  - 훅은 받은 `$?` 를 그대로 돌려준다. 뒤의 훅과 `PS1` 이 직전 명령의 상태를 본다.
  - 이미 들어 있으면 두 번 넣지 않는다(rcfile 을 다시 source 해도 하나).
  - 사용자가 셸 안에서 `exec bash` 를 하면 새 bash 는 `--rcfile` 없이 뜬다. `PROMPT_COMMAND` 를 export 해 두지 않았다면 훅이 사라져 그 뒤로 cwd 추적이 끊긴다. export 해 두었으면 아래처럼 함수도 export 되므로 이어진다.
  - 사용자가 `PROMPT_COMMAND` 를 export 해 두면 중첩 bash 가 훅 이름만 물려받아 `__tanager_prompt: command not found` 가 뜬다. 그래서 export 돼 있으면 `__tanager_prompt` · `__tanager_osc7` 도 `export -f` 한다(자식 bash 도 OSC 7 을 낸다).
  - 퍼센트 인코딩 함수 첫 줄은 `local LC_ALL=C` 다. 없으면 한글이 코드 포인트(`%D55C`)로 나간다(M0 재현).
- PowerShell 5.1
  - 원래 prompt 를 감싼다. 이미 감쌌으면 다시 감싸지 않는다.
  - 사용자가 `Set-StrictMode -Version Latest` 를 켜 두면 없는 `$global:LASTEXITCODE` 를 읽는 순간 예외가 나 호스트가 기본 `PS>` 로 물러선다. `Get-Variable -ErrorAction Ignore` 로 있는지 먼저 보고, 있었을 때만 값을 떠 두고 되돌린다. 원래 없었으면 원래 prompt 가 만든 변수를 지운다(없던 변수를 만들지 않는다).
  - 원래 prompt 를 부르기 전에 `$?` · `$LASTEXITCODE` 를 되돌려, 원래 prompt 가 직전 명령의 값을 보게 한다. `$?` 는 직접 대입할 수 없어, 실패였으면 `Write-Error -ErrorAction Ignore` 로 실패를 한 번 일으킨다.
  - FileSystem 공급자일 때만 `ESC]9;9;"<ProviderPath>"ESC\` 를 앞에 붙인다.
  - 5.1 문법만 쓴다(`` `e `` · `??` · 삼항 없음).
  - 기동은 `powershell.exe -NoLogo -NoExit -Command "if (Test-Path -LiteralPath $env:TANAGER_PS1) { . ([scriptblock]::Create([IO.File]::ReadAllText($env:TANAGER_PS1))) }"` + 환경 `TANAGER_PS1=<설치 폴더>\tanager.ps1` 이다(#81 에서 구현됨, 아래 "#81" 절).
    - 처음 안은 `-ExecutionPolicy Bypass -Command ". '<경로>'"` 였다(경로의 `'` 는 `''`).
    - #107 규칙대로 경로는 환경 변수로 바꿨다. #81 리뷰에서 Bypass 도 뺐다 — 실행 정책을 바꾸지 않는 스크립트 블록으로 읽는다.
- cmd: 환경 `PROMPT=$e]9;9;$P$e\` + (상속한 `PROMPT` 또는 `$P$G`)를 준다. 상속값은 모듈을 만들 때 한 번 읽는다. #81 에서 구현됨.
- 환경 병합: `LaunchCommand` 의 대소문자 무시 병합 규칙(#54)을 쓴다. 어댑터에는 OS 분기가 없다.

## 결과
- 스크립트 바이트(BOM · 줄바꿈 · `local LC_ALL=C`)가 단위 테스트로 고정된다. 설치기는 `@TempDir` 로 OS 와 상관없이 검사한다.
- 내용이 같으면 재시작해도 파일을 다시 쓰지 않는다. 사용자가 스크립트를 고치면 다음 시작에 되돌려진다.
- 설치에 실패하거나 `IntegrationMode.NONE` 이면 시작 경로만 기록하고 머리줄에 알린다(ADR-0003 결과 그대로, #55 · #81 이후).
- #80 에서 확인한 것(2026-11-26)
  - `bash -n tanager.bash` 통과(bash 5.2.21). 임시 HOME 에서 `bash --rcfile tanager.bash -i` 로 띄워 한글 · 공백 · `#` 경로가 UTF-8 바이트로 퍼센트 인코딩되고, 문자열 · 배열 `PROMPT_COMMAND` 모두 사용자 훅이 `false` 뒤 `$?=1` 을 보며, 다시 source 해도 OSC 가 한 번만 나오는 것을 봤다.
  - PowerShell 5.1.26100 의 `Parser::ParseFile` 오류 0건. 두 번 dot-source 해도 OSC 9;9 가 한 번만 나오고, 원래 prompt 가 `cmd /c exit 3` 뒤 `$?=False` · `$LASTEXITCODE=3` 을 보며, 레지스트리 공급자(`HKCU:\`)에서는 OSC 를 내지 않는 것을 봤다.
  - 리뷰 반영(2026-11-26)
    - bash 5.2.21: `export PROMPT_COMMAND='echo "user-hook status=$?"'` 뒤 자식 `bash -i` 에서 `command not found` 가 없고, 자식이 한글 경로를 UTF-8 퍼센트 인코딩한 OSC 7 을 내며, 자식 · 부모 모두 사용자 훅이 직전 `$?`(1 · 7)를 본다. export 하지 않은 문자열 · 배열 `PROMPT_COMMAND` 는 전과 같다(함수를 export 하지 않고, 다시 source 해도 한 번).
    - PowerShell 5.1.26100: `Set-StrictMode -Version Latest` 를 켜고 `-NoExit -Command ". '<경로>'"` 로 띄우면 고치기 전에는 첫 프롬프트가 `PS>` 였고, 고친 뒤에는 OSC 9;9 와 원래 프롬프트가 나온다. `cmd /c exit 3` 뒤 `$LASTEXITCODE=3` 이 유지되고, `LASTEXITCODE` 가 없던 세션에서는 prompt 뒤에도 없다(원래 prompt 가 `cmd /c exit 9` 를 불러도). `$Error` 는 늘지 않는다.

## #55 에서 확인한 것 (2026-11-27)
- Debian 계열(Ubuntu 포함)의 `/etc/bash.bashrc` 이중 실행(`--rcfile` + `/etc/profile`).
  - Debian 의 bash 는 `--rcfile` 을 줘도 대화형이면 `/etc/bash.bashrc` 를 먼저 읽는다(빈 rcfile 로 확인). 그런데 `tanager.bash` 가 흉내 내는 `/etc/profile` 도 대화형 bash 에서 `/etc/bash.bashrc` 를 다시 source 한다. 그래서 두 번 돈다(Ubuntu 의 sudo 안내 문구가 두 번 나온다). `~/.profile` → `~/.bashrc` 는 로그인 셸과 같아 문제가 아니다.
  - 회피책 후보: (a) `/etc/profile` 을 source 하는 동안 `PS1` 을 비워 `/etc/profile` 의 `[ "${PS1-}" ]` 분기(`bash.bashrc` 재실행)를 건너뛰게 한다. (b) `/etc/profile` 대신 `/etc/profile.d/*.sh` 만 읽는다. (c) 그대로 두고 문서에 적는다(대부분의 `bash.bashrc` 는 두 번 돌아도 무해).
  - **(a) 를 골랐다.** (b) 는 `/etc/profile` 에서 PATH 를 정하는 배포판(Fedora · Arch 등)에서 로그인 셸과 달라진다. (a) 의 부작용인 bash-completion 누락은 위 "#55 로그인 셸 설정" 처럼 보충한다.
- 실측(WSL Ubuntu 24.04.1, bash 5.2.21, 빈 임시 HOME)

  | 띄우는 방법 | sudo 안내 | `BASH_COMPLETION_VERSINFO` | PS1 |
  |---|---|---|---|
  | `bash -l -i`(기준) | 1번 | `2 11` | `${debian_chroot:+…}\u@\h:\w\$ ` |
  | #80 스크립트 | 2번 | `2 11` | 같음 |
  | PS1 만 비운 스크립트 | 1번 | 없음 | 같음 |
  | #55 최종 스크립트 | 1번 | `2 11` | 같음 |

  - `PS1` 이 unset 인 셸에서 source 해도 unset 이 유지된다. `bash -n` 통과, LF · BOM 없음 · `local LC_ALL=C` 유지.
  - 리뷰 반영 판(`/etc/bash.bashrc` 조건 · 비어 있을 때만 복원)도 같은 표의 최종 행과 같다. `/etc/bash.bashrc` 가 없는 분기는 Ubuntu 에서 조건만 바꿔 돌려 오류 없이 도는 것만 봤다(그 경우 sudo 안내는 당연히 2번).
- wsl 통합 테스트(`WindowsWslShellIntegrationTest`, 실제 `wsl.exe` + `WindowsPlatformModule` 이 만든 기동 명령, 14개 모두 실행 · 통과):
  - 첫 프롬프트 OSC 7 이 시작 경로다. 한글 · 공백 · `'` 시작 경로와 `cd` 한 경로를 그대로 알린다.
  - 한글 · 공백 · `'` 가 든 Windows 앱 데이터 경로에서도 rcfile 을 찾는다.
  - `~/.profile` 이 늘린 PATH 가 `bash -l` 과 같다. `~/.profile` → `~/.bashrc` 는 한 번만 돈다.
  - sudo 안내 한 번, PS1 · bash-completion 이 `bash -l -i` 와 같다. PS1 이 없는 비대화형 bash 에서 source 해도 PS1 이 생기지 않는다.
  - rcfile 이 없는 경로면 `bash -i` 로 물러서 `~/.bashrc` 의 별칭이 정의되고 OSC 7 은 나오지 않는다.
  - 배열 · 문자열 · export 된 `PROMPT_COMMAND` 에서 사용자 훅과 우리 훅이 모두 돌고 `$?` 를 본다. rcfile 을 다시 읽어도 훅은 하나다.
- pty4j(ConPTY, `WinConPtyProcess`)로 같은 argv · 환경을 직접 띄워(앱 데이터 폴더 `사용자 'x 공백`) 첫 프롬프트에 OSC 7 `file://<host>/tmp`, `cd ~` 뒤 홈 경로가 나오는 것을 봤다(수동 확인, 2026-11-27).
- 미검증
  - automount 루트가 `/` 인 배포판(이 PC 에는 Ubuntu-24.04 하나뿐) — `/p` 변환을 쓰므로 맞을 것으로 보지만 확인하지 않았다.
  - Fedora 계열(`/etc/bashrc` · `BASHRCSOURCED`, profile.d 두 번 읽기) — 이 PC 에 없다.
  - 기본 로그인 셸이 bash 가 아닌 사용자.
  - 앱 패키지(`Tanager.exe`)로 띄운 실제 창.

## #107 시작 경로의 명령 주입 막기 (2026-11-27)
- 문제: WSL 시작 경로는 `wsl.exe … --cd <경로> -- …` 의 argv 원소로 넘긴다. pty4j 0.14 의 `WinPtyProcess.joinCmdArgs`(ConPTY 도 같은 함수)는 공백 · 탭이 있는 원소를 `"…"` 로 감싸기만 한다. 원소 안의 `"` 는 이스케이프하지 않고, 끝 `\` 는 하나만 겹치고, `"` 로 시작하는 원소는 감싸지도 않는다. 그래서 `/tmp/x" -- touch /tmp/proof #` 같은 경로는 인자가 갈라지고, wsl.exe 가 `--` 뒤 원시 명령줄을 기본 셸의 `-c` 로 넘겨 `touch` 가 실행된다(#55 리뷰에서 재현). M3 부터는 셸이 알린 경로(OSC 7)가 다시 시작 · 트리에서 열기 · 복원의 시작 경로가 되므로, `"` 가 든 디렉터리에 들어가는 것만으로 명령이 실행될 수 있다.
- wsl.exe 의 인자 파싱(실측: WSL 2.5.10.0 · 커널 6.6.87.2-1 · Windows 10.0.26300, 리뷰어 측정 2026-11-27)
  - 따옴표 토큰은 `"` 로 시작해 **다음 `"` 에서 끝난다**. `\"` · `""` 이스케이프는 없다.
  - 비따옴표 토큰 중간의 `"` · `\` 는 글자 그대로다.
  - `--cd` 값 뒤에 남은 토큰은 `--` 가 없어도 명령으로 실행된다.
  - 유니코드 공백(U+3000 · U+00A0 등) · 따옴표 닮은 문자(U+201C · U+FF02 등)로는 인자가 갈리지 않는다.
  - 배포판 이름에는 `"` 를 쓸 수 없다(`WSL_E_INVALIDARG`).
- 선택지: (a) CommandLineToArgvW 규칙으로 직접 이스케이프한 명령줄을 pty4j `RawCommandString` 으로 넘긴다. (b) **경로의 출처에서 위험한 문자를 거부한다.**
  - **(a) 는 불가능하다.** wsl.exe 2.5.10 은 `\"` 를 따르지 않는다. 공백(감싸야 함)과 `"` 가 함께 있거나 `"` 로 시작하는 값은 어떻게 적어도 `--cd` 인자 하나로 넘길 수 없다 — 따옴표 토큰은 첫 `"` 에서 끝나고, CommandLineToArgvW 식 `\"` 는 `\` 가 글자로 남은 채 거기서 끊긴다. 공백 없는 값의 중간 `"` 만 비따옴표 토큰으로 글자 그대로 넘어간다.
- **(b) 를 골랐다.** 정상 작업 경로에 `"` · 제어 문자가 들어갈 일은 드물고, 거부해도 홈에서 띄우고 알리면 된다.
  - 출처: `WorkspacePath.of` 가 빈 값 검사 다음, 정규화 전에 `"` 와 제어 문자(0x00–0x1F, 0x7F)를 `PathError.UnsafeCharacter(raw)` 로 거부한다. WSL 은 `\` 도 거부한다(Windows 는 구분자라 허용). 이력 · 복원 · OSC 7 · `config.json` 이 모두 이 타입을 거치므로 한 곳에서 막힌다.
    - `config.json` 의 `startDir` 이 이런 경로면 기존 `StartNotice.StartDirInvalid` 로 홈에서 띄우고 알린다(새 알림을 만들지 않는다).
    - OSC 7 로 온 이런 경로는 이력에 넣지 않고 `CwdTracker` 가 오류 종류만 DEBUG 로 남긴다(경로 문자열은 남기지 않는다). `state.json` 에 이미 있던 항목은 건너뛰고 `EntriesSkipped` 로 센다.
    - `--cd` 는 셸을 거치지 않으므로 `'` · 공백 · 한글 · `$` · 백틱 · `;` · `&` · `|` · `#` 는 그대로 허용한다.
    - **C1 제어 문자(U+0080–U+009F)와 U+2028 은 허용한다.** 실측상 wsl.exe 인자를 가르지 않고(위 "유니코드 공백" 과 같다), 리눅스 파일 이름으로 정당하다. `WorkspacePath` 가 막는 제어 문자는 C0 · DEL 뿐이다. 실제 wsl.exe 통합 테스트에서 U+0085 · U+2028 이 든 경로로 그 폴더에서 셸이 뜨고 OSC 7 이 같은 경로를 알리는 것을 봤다(다른 C1 글자 · U+2029 는 모형 속성 테스트로만 봤다).
  - 방어 겹: `WindowsLaunchCommands.requireSafeArgv` 가 최종 argv 를 검사하고 어기면 `IllegalArgumentException`(환경 불일치 검사와 같은 종류)을 던진다. `SessionStarter` 는 이 예외를 잡지 않는다 — 정상 흐름에서는 도달할 수 없는 버그이고, `LaunchFailed` 로 바꾸면 평범한 기동 실패로 숨는다.
    - 감싸질 원소(공백 · 탭이 있는 원소)에 `"` 가 있거나 `\` 로 끝나면 거부한다(모든 셸).
    - WSL 은 `--` 앞 원소에 `"` 가 있기만 해도 거부한다. `"` 로 시작하는 원소는 pty4j 가 감싸지 않고 wsl.exe 는 다음 `"` 까지 한 인자로 읽어, 감싸지 않는 원소도 뒤 인자를 삼킬 수 있다. 템플릿이 `--` 를 잘못 넣어도 검사에서 빠지지 않게 마지막 `--` 까지 본다. `--` 뒤는 리눅스 셸이 읽는 원시 명령줄이라 `"$TANAGER_RC"` 는 그대로 둔다.
    - **pty4j 를 올려 `joinCmdArgs` 가 `\"` 이스케이프를 하게 돼도 이 검사를 지우지 않는다.** wsl.exe 는 `\"` 를 따르지 않으므로 그 이스케이프는 wsl 에서는 오히려 인자를 가른다.
- 이 검사는 **wsl.exe 인자 분리만 막는다.** cmd `/c` · `/k` · PowerShell `-Command` 문자열에 사용자 경로를 넣지 않는다(`lpCurrentDirectory` · 환경 변수로 넘긴다). 불가피하면 셸별 이스케이프와 테스트를 둔다.
  - 지금 PowerShell · cmd 는 작업 디렉터리를 argv 가 아니라 `CreateProcess` 의 `lpCurrentDirectory` 로 넘긴다(`LaunchCommand.workingDirectory`, ADR-0007). #81 은 PowerShell 스크립트 경로(앱 데이터, 사용자 이름 포함)도 `-Command` 문자열이 아니라 환경 변수 `TANAGER_PS1` 로 넘긴다 — 이스케이프가 필요 없다(아래 "#81" 절).
- 확인한 것
  - `WslCommandLineRoundTripTest`(OS 무관): pty4j 0.14.0 `joinCmdArgs` 사본과 위 파싱 규칙 모형(`WslCommandLineModel`)으로, `WorkspacePath` 가 허용한 임의의 WSL 경로(공백 · `'` · `$` · 백틱 · 한글 · C1 · U+2028 · 유니코드 공백 · 따옴표 닮은 문자)로 만든 argv 가 명령줄로 이었다 다시 읽어도 같다. `WorkspacePath` 를 우회해도 방어 검사를 통과한 argv 는 같다(속성 각 1000회). 감싸질 원소의 `"` 규칙을 끄면 속성이 실패한다. `--` 앞 `"` 규칙은 `--cd` 값이 늘 `/` · `~` 로 시작해 속성으로는 드러나지 않으므로(토큰 중간 `"` 는 글자 그대로) 템플릿 원소 단위 테스트로 본다.
  - `WindowsWslCdInjectionTest`(`@Tag("wsl")`, 실제 wsl.exe): `q" -- touch …/proof #` 는 두 곳에서 거부된다. 두 검사를 모두 우회한 명령줄은 실제로 proof 를 만든다(하네스 민감도). 허용되는 `'` · `$` · 백틱 · 한글 · U+3000 · U+0085 · U+2028 · `-- touch` · `#` 가 든 경로는 그 폴더에서 뜨고 proof 는 생기지 않는다. pty4j 는 adapter.pty 에 가둬 두었으므로, 같은 명령줄을 PowerShell 5.1 `ProcessStartInfo.Arguments` 로 `CreateProcess` 에 넘긴다(다른 점은 .NET 이 argv[0] 을 `"…"` 로 감싸는 것뿐).
- 미검증
  - 줄바꿈 · 기타 C0 제어 문자가 wsl.exe 인자를 가르는지. `WorkspacePath` 가 거부하므로 확인하지 않았고, 모형은 공백 · 탭만 구분자로 본다(방어 겹은 제어 문자를 따로 보지 않는다).
  - 닫는 `"` 바로 뒤에 글자가 붙은 토큰(`"a"b`)의 처리 — 모형은 추측하지 않고 실패로 본다.
  - pty4j ConPTY(`WinConPtyProcess`) 로 띄운 실제 세션에서의 같은 확인 — 명령줄이 `joinCmdArgs` 로 같다는 것까지만 소스로 봤다.

## #81 PowerShell 5.1 · cmd 주입 (2026-11-27, 구현됨)
- 셸은 `ShellProfile.kind()`(`WINDOWS_POWERSHELL` · `CMD`)로 가른다. `Integration.Unavailable` 이면 두 셸 모두 M2 와 같다(템플릿 인자 그대로, 환경 변수 없음). 작업 디렉터리는 둘 다 계속 `lpCurrentDirectory` 다.
- PowerShell(Installed)
  - argv: 템플릿(`powershell.exe -NoLogo`) 뒤에 `-NoExit -Command "if (Test-Path -LiteralPath $env:TANAGER_PS1) { . ([scriptblock]::Create([IO.File]::ReadAllText($env:TANAGER_PS1))) }"`. 환경 `TANAGER_PS1=<설치 폴더>\tanager.ps1`(`Path.toString`), 이름 비교는 대소문자 무시.
  - `-Command` 문자열은 고정이다. 사용자 이름이 든 앱 데이터 경로가 PowerShell 의 따옴표 규칙(`'` · U+2018–U+201B)을 타지 않는다. 변수 값은 다시 단어로 갈리지 않으므로 공백 경로도 그대로 읽힌다.
  - 이 원소는 공백이 있어 pty4j 가 `"…"` 로 감싸지만 `"` 가 없고 `\` 로 끝나지 않아 `requireSafeArgv` 를 통과한다(단위 테스트).
  - 파일이 없으면(지워짐) `Test-Path` 가 거짓이라 조용히 건너뛴다. 통합 없이 기본 프롬프트로 뜨고 오류 출력이 없다.
  - 스크립트 블록이라 `$PSScriptRoot` · `$MyInvocation.MyCommand.Path` 는 비어 있다. `tanager.ps1` 은 이 값들을 쓰지 않는다. `ReadAllText` 는 BOM 을 보고 UTF-8 로 읽고 BOM 을 떼어 낸다(BOM 은 파일로 직접 dot-source 할 때를 위해 유지).
- **실행 정책: Bypass 를 쓰지 않고 스크립트 블록으로 읽는다(#81 리뷰).**
  - `-ExecutionPolicy Bypass` 를 쓰지 않은 이유는 "프로세스 범위만" 이 아니어서다. 실측(이 PC, LocalMachine=RemoteSigned)은 이렇다.

    | | `-NoProfile` 만 | `-NoProfile -ExecutionPolicy Bypass` |
    |---|---|---|
    | `Get-ExecutionPolicy` | RemoteSigned | Bypass |
    | `-List` 의 Process | Undefined | Bypass |
    | `$env:PSExecutionPolicyPreference` | 없음 | `Bypass` |
    | 자식 `powershell -NoProfile` 의 실효 정책 | RemoteSigned | Bypass |

    - Bypass 는 세션 전체와 그 세션이 띄우는 자식 PowerShell 에 환경 변수로 샌다.
    - `$PROFILE` 도 Bypass 로 읽힌다.
    - 그래서 사용자가 고른 RemoteSigned · AllSigned 를 사용자 모르게 덮는다.
  - 실행 정책은 **스크립트 파일**(과 프로필 · 모듈 파일)을 불러올 때만 검사된다. 문자열로 만든 스크립트 블록(`[scriptblock]::Create`)을 dot-source 하는 것은 명령을 직접 치는 것과 같아 검사 대상이 아니다.
  - 실측
    - 세션 정책을 AllSigned 로 둔 `powershell -NoProfile -ExecutionPolicy AllSigned -NoExit -Command "<위 문자열>"` 에서도 서명 없는 `tanager.ps1` 이 읽혔다(OSC 9;9 · prompt 감쌈 · `cd ..` 뒤 OSC). 정책은 AllSigned 그대로였다.
    - 대조: 서명 없는 스크립트를 파일로(`. '<경로>'`) dot-source 하면, RemoteSigned 에서 UNC(`\\wsl.localhost\…`) 경로의 것은 `UnauthorizedAccess` 로 막혔다. 같은 내용을 스크립트 블록으로 읽으면 막히지 않았다.
  - 그룹 정책(`MachinePolicy` · `UserPolicy`)도 같은 파일 검사이므로 스크립트 블록을 막지 않을 것으로 본다(GPO 를 걸 수 없어 미검증). 위험 R4 는 "실행 정책이 통합을 막는다" 에서 "언어 모드가 통합을 막는다"(아래 CLM)로 바뀐다.
- cmd(Installed)
  - argv 는 템플릿 그대로(`cmd.exe`, `/k` 없음). 환경 `PROMPT=$e]9;9;$P$e\` + base. base 는 모듈을 만들 때 읽은 `PROMPT` 이고, 없거나 공백뿐이면 `$P$G` 다.
  - 상속값이 이미 접두로 시작하면(대소문자 무시 — cmd 의 `$` 코드가 대소문자를 가리지 않으므로) 다시 붙이지 않는다. Tanager 안에서 Tanager 를 띄워도 OSC 가 두 번 나오지 않고, 병합은 멱등이다(속성 테스트).
  - OSC 9;9 값에 따옴표가 없다. `OscScanner` 는 따옴표 없는 값도 읽는다(ADR-0015, 픽스처 `osc99-unquoted.bin`).
  - PROMPT 에는 앱 데이터 경로가 들어가지 않는다.
- 테스트
  - `WindowsPowerShellCmdInjectionTest`(OS 무관 17개): 위 argv · 환경, `'` · 공백 · 한글 · U+2018–U+201B 가 든 앱 데이터 경로가 argv · PROMPT 어디에도 나타나지 않음(속성), PROMPT 병합 멱등(속성), 모듈이 `PROMPT` 를 생성 때 한 번만 읽음.
  - `WindowsPowerShellCmdShellIntegrationTest`(`@Tag("windows")`, 15개 모두 실행 · 통과, 건너뜀 0)
    - 실제 `powershell.exe` · `cmd.exe` 를 모듈이 만든 argv · 작업 디렉터리 · 환경 그대로 파이프로 띄운다.
    - 하네스가 바꾸는 것은 셋이다. PowerShell 에 `-NoProfile`, cmd 에 `/d`(AutoRun 격리)를 끼운다. 사용자 프로필 흉내 스크립트도 스크립트 블록으로 읽는다(PC 의 실행 정책이 결과를 바꾸지 않게). 파이프 출력은 OEM 코드 페이지(이 PC 949)로 읽어 UTF-8 로 다시 인코딩한 뒤 `OscScanner` 에 넣는다.
    - PowerShell: `%TEMP%` 아래 `한글 폴더 #1` 에서 첫 프롬프트 · `cd ..` 뒤 OSC 9;9 두 번, 경로 일치. 앱 데이터가 `O'Neil 한글 앱` 이어도 스크립트를 읽고 오류가 없다. 사용자 prompt(oh-my-posh 흉내) · `Set-StrictMode -Version Latest` 아래에서도 사용자 prompt 글자와 OSC 가 모두 나온다(`PS>` 로 물러서지 않음). `cmd /c exit 3` 뒤 원래 prompt 가 `$LASTEXITCODE=3` · `$?=False`, 성공 뒤 `$?=True` 를 본다. 두 번 dot-source 해도 프롬프트마다 OSC 한 번. `HKCU:\` 에서는 OSC 없음.
    - PowerShell 실행 정책: 범위별 정책 · 실효 정책 · 자식 PowerShell 의 실효 정책이 맨 `powershell -NoProfile` 과 같다. `$env:PSExecutionPolicyPreference` 가 없다. `TANAGER_PS1` 이 없는 파일을 가리키면 오류 없이 기본 프롬프트로 뜨고 OSC 가 없다. 세 테스트는 Bypass 판에서 실패하는 것을 먼저 확인했다.
    - cmd: 같은 한글 · 공백 폴더에서 첫 프롬프트 · `cd ..` 뒤 따옴표 없는 OSC 9;9, 경로 일치. 특이한 앱 데이터 경로에서도 OSC 가 나온다. 사용자 `PROMPT=[IT] $P$G` 글자가 유지되고, 없으면 `경로>` 모양이다.
  - 실측 환경: Windows 10.0.26300, PowerShell 5.1.26100.9818, 콘솔 코드 페이지 949.
- 앱 패키지(`:bootstrap:jpackageImage` → `Tanager.exe`)로 확인(2026-11-27): `config.json` 의 `defaultProfile` 을 `powershell` · `cmd` 로 두고 각각 띄워 8초 뒤 창을 닫았다.
  - 자식 셸 명령줄(스크립트 블록 판): `powershell.exe -NoLogo -NoExit -Command "if (Test-Path -LiteralPath $env:TANAGER_PS1) { . ([scriptblock]::Create([IO.File]::ReadAllText($env:TANAGER_PS1))) }"` · `cmd.exe`. PowerShell 은 `PSExecutionPolicyPreference` 를 지운 런처에서 띄웠다(이 PC 의 실효 정책 RemoteSigned).
  - cmd 는 첫 판에서, PowerShell 은 첫 판(Bypass)과 스크립트 블록 판에서 각각 봤다. 모든 경우 `state.json` 에 Windows 홈(`C:\Users\<사용자>`)이 `visitCount 1` 로 기록됐다. Installed 일 때 시작 경로는 `SessionStarter` 가 아니라 `CwdTracker`(OSC) 만 기록하므로 셸이 낸 OSC 9;9 로 기록된 것이다.
- 알려진 부작용과 미검증
  - **Constrained Language Mode**(AppLocker · WDAC 가 강제)
    - 이 모드에서는 `[IO.File]::ReadAllText` 같은 메서드 호출이 막힌다.
    - 세션에서 `$ExecutionContext.SessionState.LanguageMode` 를 ConstrainedLanguage 로 바꿔 흉내 낸 실측에서는 오류 하나(`MethodInvocationNotSupportedInConstrainedLanguage`)가 보였다. 셸은 기본 프롬프트로 떴고 OSC 는 없었다(통합 없음).
    - `__PSLockdownPolicy=4` 로는 이 PC(26300)에서 CLM 이 켜지지 않았다.
    - 실제 AppLocker · WDAC 정책 아래에서는 확인하지 않았다(미검증). 그때도 오류 하나 + 통합 없음일 것으로 보지만 추측이다.
  - **cmd 의 Clink · AutoRun**: AutoRun(`HKCU|HKLM\Software\Microsoft\Command Processor\AutoRun`)이나 Clink 의 프롬프트 필터가 `PROMPT` 를 바꾸거나 덮으면 OSC 9;9 가 사라질 수 있다. 운영 argv 에는 `/d` 를 넣지 않는다(사용자의 AutoRun 을 존중). 이 PC 에는 AutoRun 이 없어 확인하지 않았다(미검증).
  - **`@echo off` 없는 `.bat`**
    - 배치는 명령마다 프롬프트를 출력하므로 우리 OSC 9;9 도 함께 나온다(실측: `cd C:\Windows` 를 담은 배치를 파일로 리디렉션하면 로그에 `ESC]9;9;C:\Windows ESC\C:\Windows>echo hi` 가 남는다).
    - 그래서 배치 안의 `cd` 가 이력에 기록될 수 있다. 대부분은 배치가 끝난 뒤에도 cmd 의 cwd 가 실제로 바뀌므로 맞는 기록이지만, `setlocal` · `pushd/popd` 로 되돌리는 배치는 지나간 경로가 남는다.
    - 리디렉션한 로그에는 ESC 시퀀스가 섞인다.
    - `@echo off` 배치에서는 프롬프트가 나오지 않는다(실측).
  - 실제 oh-my-posh · `$PROFILE` 을 쓰는 사용자 환경. 통합 테스트는 `-NoProfile` 에 테스트 스크립트로 흉내만 냈다.
  - 사용자 `PROMPT` 가 이미 다른 OSC(예: Windows Terminal 의 OSC 9;9 · 133)를 넣어 둔 경우의 중복.
  - 한글을 표현하지 못하는 OEM 코드 페이지(437 등) PC 의 통합 테스트 — 한글 테스트는 건너뛰도록 돼 있고, 이 PC(949)에서는 모두 돌았다.
