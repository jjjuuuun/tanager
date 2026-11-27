# 0003. 작업 디렉터리 추적: 셸 통합 + OSC 7 / OSC 9;9

- 상태: 채택 — M0 스파이크(#10, 2026-11-24)로 확인
- 날짜: 2026-11-24
- 관련: SDD §5.2, §6.3

## 맥락
핵심 기능은 "각 터미널이 지금 어느 경로에 있는지"를 알아 이력으로 남기는 것이다. Windows 프로세스에서 WSL 안 셸의 cwd 를 직접 읽을 방법이 없고, PowerShell 도 외부에서 현재 위치(provider path)를 알 수 없다.

## 선택지
1. 프로세스 cwd 조회 (`/proc/<pid>/cwd`, Win32 PEB) — WSL 경계를 못 넘고 PowerShell `Set-Location` 은 프로세스 cwd 를 바꾸지 않음.
2. 입력 가로채기(`cd` 명령 파싱) — 별칭·스크립트·`pushd`·탭 완성을 못 따라감.
3. **셸 통합 스크립트가 프롬프트마다 OSC 시퀀스로 cwd 를 알림** — Windows Terminal·VS Code·iTerm2 가 쓰는 방식.

## 결정
3번. bash/zsh 는 `OSC 7 ; file://host/퍼센트인코딩경로 ST`, PowerShell 은 `OSC 9 ; 9 ; "C:\경로" ST`. PTY 출력 스트림을 에뮬레이터로 넘기기 전에 상태 기반 스캐너가 시퀀스를 읽는다(에뮬레이터에는 그대로 전달 — 표시되지 않는 시퀀스).
세션 기동 시 사용자 rc 를 먼저 source 한 뒤 프롬프트 훅을 덧붙이므로 사용자 설정을 덮어쓰지 않는다.

## 결과
- 사용자가 프롬프트를 통째로 바꾸는 테마(oh-my-posh, starship)를 써도 동작해야 한다 → 프롬프트 함수를 감싸는 방식으로 구현하고 M0 에서 확인.
- 셸 통합 주입이 실패한 세션(스크립트 설치 실패 등)은 기동 cwd 만 기록하고 창 머리줄에 알린다.

## M0 검증 결과 (2026-11-24, 이슈 #10)
- bash(OSC 7) · Windows PowerShell 5.1(OSC 9;9) · cmd(`PROMPT` 의 OSC 9;9) 모두 첫 프롬프트와 `cd '<임시>\한글 폴더 #1'` 뒤 cwd 를 정확히 가로챘다. JediTerm 은 이 시퀀스를 화면에 그리지 않는다.
- **`local LC_ALL=C` 는 필수**: 스파이크 구현에서 이 줄을 빠뜨리자 UTF-8 로캘에서 글자 단위로 돌아 한글이 `%D55C` 처럼 코드 포인트로 나갔다. SDD §10.2 스크립트에는 이미 있으며, 빼면 안 된다는 것을 확인했다.
- PowerShell 5.1 스크립트는 UTF-8 **BOM** 으로 저장해야 한글 주석 · 문자열이 깨지지 않는다. M0 은 `-ExecutionPolicy Bypass` 로 실행 정책을 우회했다. 그러나 Bypass 는 프로세스 범위에만 머물지 않는다 — `PSExecutionPolicyPreference` 로 세션 · 자식 PowerShell 에 새고 `$PROFILE` 도 Bypass 로 읽힌다. 그래서 #81 부터는 Bypass 없이 스크립트를 문자열 스크립트 블록으로 읽는다(실행 정책 검사 대상이 아님, ADR-0014 #81).
- 스캐너는 read 경계를 넘는 시퀀스 · BEL/ST 두 종료자를 처리한다(스파이크 `OscScanner`). 큰 출력 중 지연은 M3 에서 측정.
- 미확인(M3): oh-my-posh · starship 같은 프롬프트 테마와의 공존 — 이 PC 에 설치돼 있지 않았다.

## 개정: WSL bash 의 로그인 셸 설정 (#55, 2026-11-27)
- 맥락: 셸 통합을 주입하려면 `bash --rcfile <tanager.bash> -i` 로 띄워야 하고, `--rcfile` 은 `-l` 과 함께 쓸 수 없다. 그러면 `~/.profile` 이 읽히지 않아 PATH 가 그냥 `wsl.exe` 를 띄울 때(로그인 셸)와 달라진다.
- 결정: rcfile 이 bash 로그인 셸 순서를 흉내 낸다 — `/etc/profile` → `~/.bash_profile` | `~/.bash_login` | `~/.profile` 중 처음 있는 것 → 셋 다 없으면 `~/.bashrc`. 그 뒤에 프롬프트 훅을 덧붙인다(위 "사용자 rc 를 먼저 source" 그대로). 세부(Debian `/etc/bash.bashrc` 이중 실행 회피, bash-completion 보충)와 실측은 ADR-0014.
- 결과: `~/.profile` 에서 늘린 PATH 가 로그인 셸과 같고, `~/.profile` 이 `~/.bashrc` 를 읽어도 `.bashrc` 는 한 번만 돈다(wsl 통합 테스트).

## M3 확인 결과 (#93, 2026-11-27)
- **프롬프트 테마 공존**: 테마가 하는 일을 흉내 낸 자동 테스트로 확인했다. 실제 oh-my-posh(winget) · starship 설치 확인은 사용자 PC 에 서드파티 프로그램을 깔아야 해서 v0.3.0 릴리스 수동 확인 항목으로 넘긴다.
  - PowerShell: oh-my-posh 처럼 `prompt` 를 먼저 정의해도 테마 출력이 유지되고, OSC 9;9 와 직전 명령의 `$?` · `$LASTEXITCODE` 가 그대로다. `Set-StrictMode -Version Latest` 에서도 같다(`WindowsPowerShellCmdShellIntegrationTest`).
  - bash: starship · bash-preexec 처럼 `PROMPT_COMMAND` 를 문자열 · 배열 · export 된 값으로 써도 사용자 훅과 우리 훅이 모두 돌고, 사용자 훅이 직전 명령의 `$?` 를 본다(`WindowsWslShellIntegrationTest`).
  - cmd: 사용자 `PROMPT` 가 유지된다.
- **큰 출력 중 비용**: 50MB(색 이스케이프가 섞인 `ls` 모양 출력, 16KB 조각)를 추적기에 먹인 처리량을 쟀다.
  - 처음에는 230MB/s 였다. 스캐너가 이스케이프 사이의 평문도 한 바이트씩 상태 기계로 돌렸기 때문이다.
  - 평문은 다음 ESC 로 바로 건너뛰게 바꿔 2,230MB/s 가 됐다(이 PC, WSL).
  - 화면 쪽(ConPTY + JediTerm) 처리량을 넉넉히 40MB/s 로 잡으면 추적 비용은 2% 안이다. `CwdTrackerThroughputTest` 는 처리량을 같은 실행에서 잰 기준선(ESC 만 찾는 가장 단순한 루프)과 비교해, 그 0.30 배 아래로 떨어지면 실패한다(#164). 절대 처리량 기준은 다른 빌드와 겹친 부하에서 흔들렸다. 지금 스캐너는 0.58 ~ 0.84, 최적화 전 스캐너는 0.10 ~ 0.16 이었다.
  - 화면 처리량은 추정값이다. 실제 파이프라인에서 추적을 켜고 끈 비교는 릴리스 수동 확인 항목이다.
- **한글 · 공백 · `'` 가 든 경로**: 세 셸 모두 확인했다. 대상은 시작 경로 · `cd` 한 경로 · 앱 데이터(셸 통합 스크립트) 경로다(wsl · windows 태그 통합 테스트).
- **패키지**: 설치한 앱에서 스크립트 설치 위치(`%APPDATA%\Tanager\shell-integration\`)와 이력 저장(`state.json`)을 확인했다. 세 셸의 OSC 가 모두 기록됐다(#81 · #55 PR 의 수동 확인).
