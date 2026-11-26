# 0014. 셸 통합 스크립트 배포와 주입

- 상태: 채택(#80, 2026-11-26) — 배포는 #80, WSL 주입은 #55(2026-11-27)에서 구현했다. PowerShell · cmd 주입은 #81 에서 구현한다.
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
- argv 는 `wsl.exe -d <d> --cd <경로|~> -- bash --rcfile "$TANAGER_RC" -i` 다. 작업 디렉터리는 비운다(ADR-0007).
  - `wsl.exe` 는 `--` 뒤 명령을 리눅스 기본 셸(`$SHELL -c`)로 실행하므로 변수가 거기서 펼쳐진다.
  - argv 원소는 큰따옴표를 **포함한** `"$TANAGER_RC"` 다. 따옴표가 없으면 기본 셸이 펼친 경로를 공백에서 가른다 — Windows 사용자 이름에 공백이 있으면 rcfile 을 못 찾는다(실측: `[…/사용자]` `['x]` `[공백/tanager.bash]` 셋으로 갈림). 작은따옴표는 펼쳐지지 않는다.
  - 공백 · 탭이 없는 원소라 Windows 명령줄로 옮길 때 다시 감싸지 않는다. JDK `ProcessBuilder` 와 pty4j 0.14 의 `joinCmdArgs`(ConPTY 도 같은 함수) 모두 그대로 넘긴다.
- 기존 `WSLENV` 는 모듈을 만들 때 환경에서 한 번 읽는다(순수 계산 유지). 병합 규칙:
  1. `:` 로 나누고 앞뒤 공백을 지운 뒤 빈 항목을 버린다.
  2. 이름(`/` 앞)이 `TANAGER_RC` 인 항목은 대소문자를 무시하고 지운다 — Tanager 안에서 Tanager 를 띄우면 상속한 WSLENV 에 이미 있다.
  3. 나머지는 순서대로 두고 맨 뒤에 `TANAGER_RC/p` 하나를 붙인다.
- `/p` 변환은 한글 · 공백 · `'` 가 든 앱 데이터 경로에서도 맞게 동작했다. 그래서 (a) `/mnt/` 직접 계산으로 물러서지 않는다 — 직접 계산해도 같은 따옴표 문제가 남는다.
- `Unavailable` 이면 M2 명령(`… -- bash -i`) 그대로 띄우고 환경 변수를 더하지 않는다. PowerShell · cmd 는 #81 전까지 그대로다.

### #55 로그인 셸 설정
- `tanager.bash` 가 bash 로그인 셸 순서를 흉내 낸다: `/etc/profile` → `~/.bash_profile` | `~/.bash_login` | `~/.profile` 중 처음 있는 것 → 셋 다 없으면 `~/.bashrc`. 그냥 `wsl.exe` 를 띄울 때(로그인 셸)와 PATH 가 같다. VS Code 셸 통합과 같은 방식이다.
- Debian 계열 `/etc/bash.bashrc` 이중 실행을 막으려고 `/etc/profile` 을 source 하는 동안만 `PS1` 을 비운다(아래 "#55 에서 확인한 것" 의 회피책 (a)).
  - 끝나면 원래 값으로 되돌린다. 원래 unset 이었으면 unset 으로 되돌린다.
  - 비운 `PS1` 때문에 `/etc/profile.d/bash_completion.sh` 도 completion 을 불러오지 않는다(`PS1` 이 있을 때만 불러온다). 그래서 되돌린 뒤 `BASH_COMPLETION_VERSINFO` 가 없고 그 파일을 읽을 수 있으면 직접 source 한다. 로그인 셸과 같아진다.
- 스크립트 내용은 #80 에 넣었고, 동작 검증(@Tag wsl)은 #55 에서 했다. ADR-0003 에 개정 절을 남겼다.

### 훅 공존 (스크립트는 #80, 검증은 #55 · #81)
- bash
  - `PROMPT_COMMAND` 가 배열(bash 5.1+)이면 원소를 더하고, 문자열이면 앞에 붙인다.
  - 훅은 받은 `$?` 를 그대로 돌려준다. 뒤의 훅과 `PS1` 이 직전 명령의 상태를 본다.
  - 이미 들어 있으면 두 번 넣지 않는다.
  - 사용자가 `PROMPT_COMMAND` 를 export 해 두면 중첩 bash 가 훅 이름만 물려받아 `__tanager_prompt: command not found` 가 뜬다. 그래서 export 돼 있으면 `__tanager_prompt` · `__tanager_osc7` 도 `export -f` 한다(자식 bash 도 OSC 7 을 낸다).
  - 퍼센트 인코딩 함수 첫 줄은 `local LC_ALL=C` 다. 없으면 한글이 코드 포인트(`%D55C`)로 나간다(M0 재현).
- PowerShell 5.1
  - 원래 prompt 를 감싼다. 이미 감쌌으면 다시 감싸지 않는다.
  - 사용자가 `Set-StrictMode -Version Latest` 를 켜 두면 없는 `$global:LASTEXITCODE` 를 읽는 순간 예외가 나 호스트가 기본 `PS>` 로 물러선다. `Get-Variable -ErrorAction Ignore` 로 있는지 먼저 보고, 있었을 때만 값을 떠 두고 되돌린다. 원래 없었으면 원래 prompt 가 만든 변수를 지운다(없던 변수를 만들지 않는다).
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
- wsl 통합 테스트(`WindowsWslShellIntegrationTest`, 실제 `wsl.exe` + `WindowsPlatformModule` 이 만든 기동 명령, 12개 모두 실행 · 통과):
  - 첫 프롬프트 OSC 7 이 시작 경로다. 한글 · 공백 · `'` 시작 경로와 `cd` 한 경로를 그대로 알린다.
  - 한글 · 공백 · `'` 가 든 Windows 앱 데이터 경로에서도 rcfile 을 찾는다.
  - `~/.profile` 이 늘린 PATH 가 `bash -l` 과 같다. `~/.profile` → `~/.bashrc` 는 한 번만 돈다.
  - sudo 안내 한 번, PS1 복원, bash-completion 이 로그인 셸과 같다.
  - 배열 · 문자열 · export 된 `PROMPT_COMMAND` 에서 사용자 훅과 우리 훅이 모두 돌고 `$?` 를 본다. rcfile 을 다시 읽어도 훅은 하나다.
- pty4j(ConPTY, `WinConPtyProcess`)로 같은 argv · 환경을 직접 띄워(앱 데이터 폴더 `사용자 'x 공백`) 첫 프롬프트에 OSC 7 `file://<host>/tmp`, `cd ~` 뒤 홈 경로가 나오는 것을 봤다(수동 확인, 2026-11-27).
- 미검증: automount 루트가 `/` 인 배포판(이 PC 에는 Ubuntu-24.04 하나뿐) — `/p` 변환을 쓰므로 맞을 것으로 보지만 확인하지 않았다. 앱 패키지(`Tanager.exe`)로 띄운 실제 창.
