# 0009. 시작 경로 표현(WorkingDir)과 config.json 읽기 · 쓰기 계약

- 상태: 채택(#44, 2026-11-25)
- 날짜: 2026-11-25
- 관련: #44, SDD §4.1 · §6.2 · §9.3 · §9.4, FR-SES-02 · 04, NFR-02, ADR-0007(개정), #53

## 맥락
#44 에서 앱이 "어떤 셸을 어느 경로에서" 띄울지 정한다. 기본 경로는 "그 환경의 홈"이다. 두 가지가 나중에 바꾸기 어렵다.

1. **WSL 홈은 띄우기 전에 알 수 없다.**
   - `WorkspacePath` 는 절대 경로만 받는다. 그래서 `~` 를 담을 수 없다.
   - 홈을 알아내려면 `wsl.exe -d <배포판> -e sh -c 'echo $HOME'` 를 한 번 더 실행해야 한다.
   - 배포판이 꺼져 있으면 VM 이 뜨는 동안 수 초가 걸린다. 첫 창 2초(NFR-02)를 넘긴다.
   - `wsl.exe --cd ~` 는 문서화된 기능이다. 셸을 띄우면서 리눅스 사용자의 홈으로 들어간다.
2. **config.json 은 사용자가 손으로 고치는 파일이다.**
   - 주석이 있을 수 있다(SDD §9.3 예시에도 `//` 가 있다).
   - M2 가 모르는 키(appearance, keys 등)가 들어 있다.
   - 상위 버전 파일이 올 수 있다(§9.4 다운그레이드 보호).

## 선택지
**홈 표현**
1. 기동 전에 WSL 홈을 조회해 `WorkspacePath` 로 만든다. 배포판마다 수 초가 걸릴 수 있고 기동 경로가 둘로 갈린다.
2. 홈을 `/home/<Windows 사용자명>` 으로 추측한다. root 나 이름이 다른 사용자에서 틀린다.
3. **도메인에 `WorkingDir = Home | At(WorkspacePath)` 를 둔다.** 홈을 실제 경로로 바꾸는 일은 플랫폼이 기동 명령을 만들 때 한다.
   - WSL 은 `--cd ~` 로 넘긴다.
   - Windows 는 `%USERPROFILE%` 를 작업 디렉터리로 쓴다.

**모르는 키 보존**
1. 값 타입에 원본 JSON 트리를 들고 다닌다. application 이 Jackson 을 알게 된다.
2. 어댑터가 마지막으로 읽은 트리를 기억한다. 상태가 생기고 그사이 손으로 고친 내용을 덮는다.
3. **저장할 때 디스크의 파일을 다시 읽고, 아는 키만 덮어쓴 뒤 원자적으로 쓴다.**

## 결정
- **시작 경로**
  - `dev.tanager.domain.session.WorkingDir`(sealed: `Home` | `At(WorkspacePath)`)를 둔다.
  - `SessionState` 의 `Starting.dir` · `Running.cwd` · `Exited.lastCwd` 는 `WorkingDir` 가 된다.
  - `TerminalSession.cwdChanged(WorkspacePath)` 는 `At` 으로 바꾼다.
  - `PlatformModule.launchCommand(ShellProfile, WorkingDir)` 로 바꾼다(ADR-0007 개정).
    - 순수 계산은 그대로다.
    - Windows 홈은 모듈을 만들 때 환경 변수에서 한 번 읽는다.
- **Windows 경로가 있는지는 미리 확인하지 않는다.**
  - `Pty4jLauncher` 가 이미 `LaunchError.WorkingDirectoryNotFound` 를 돌려준다(ADR-0007).
  - 그 오류가 오면 `StartSession` 이 같은 프로파일을 `Home` 에서 한 번 더 띄우고 알린다. 확인과 기동 사이에 경로가 사라지는 경쟁 조건도 생기지 않는다.
  - WSL 경로 확인은 `PlatformModule.paths()` 와 함께 #53(M5)에서 한다.
- **config.json 읽기**
  - 위치: `PlatformModule.dirs().appData()/config.json`. 이름은 SDD §4.1 의 `dirs()` 를 따른다.
  - Jackson 3 트리 모델로 읽는다. `//` · `/* */` 주석과 UTF-8 BOM 을 허용한다.
  - 아는 키만 꺼낸다. 모르는 키는 무시한다.
  - 아는 키의 형식이 틀리면 그 키만 기본값으로 두고 경고를 남긴다.
  - 문법 오류나 루트가 객체가 아니면 `ConfigError.Corrupt` 다. 이때 application 이 기본값으로 시작하고 알린다.
  - 파일이 없으면 오류가 아니라 기본값이다.
  - `schemaVersion` 이 없으면 1 로 본다. 1 보다 크면 읽을 수 있는 만큼 읽고 경고를 남긴다. 그 파일은 읽기 전용이다.
- **config.json 쓰기**(`save`)
  - 디스크의 파일을 다시 읽는다 → 아는 키(`schemaVersion` · `defaultProfile` · `profiles.<id>.startDir`)만 덮어쓴다 → `config.json.tmp` 에 쓴다 → `ATOMIC_MOVE` + `REPLACE_EXISTING` 으로 교체한다.
  - 디스크의 파일이 손상됐거나 상위 버전이면 쓰지 않고 오류를 돌려준다. 손상 파일은 절대 덮어쓰지 않는다.
  - 주석은 저장하면 사라진다. 그래서 **M2 는 config 를 자동으로 저장하지 않는다**.
    - 기본 프로파일이 사라져 다른 프로파일로 띄울 때도 그 결정은 실행 중에만 쓴다.
    - 배포판을 다시 설치하면 사용자의 설정이 그대로 다시 맞는다.
- **시작 경로 키는 프로파일별로 둔다**: `profiles: { "<profileId>": { "startDir": "<경로>" } }`.
  - 경로의 의미는 환경에 따라 다르고, 환경은 프로파일이 정한다. 그래서 환경이 어긋나는 상태를 만들 수 없다.
  - SDD §9.3 에 이 키를 더한다.
  - 필드를 더하는 것이라 schemaVersion 은 1 그대로다(§9.4).
- **M2 에는 StateActor · UiDispatcher 를 두지 않는다.**
  - 창이 하나이고 공유되는 도메인 상태가 없다.
  - 세션 값은 `StartedSession` 이 불변 값으로 넘긴다. 종료는 `CompletionStage<TerminalSession>` 으로 알린다.
  - EDT 로 넘기는 일은 UI 가 한다.
  - 두 가지는 공유 상태(WorkHistory)가 생기는 M3 에서 도입한다.

## 결과
- 홈에서 띄울 때 프로세스를 따로 실행하지 않아 첫 창 시간이 늘지 않는다.
- 대신 홈에서 띄운 세션은 셸 통합(M3)이 알려 주기 전까지 실제 cwd 문자열을 모른다. 창 머리줄은 `~` 로 보인다.
- #41 의 `SessionState` · `TerminalSession` 테스트와 #42 · #43 의 `launchCommand` 테스트를 `WorkingDir` 로 고친다. 아직 저장된 state.json 이 없으므로 마이그레이션은 필요 없다.
- 후속
  - #46: 조립(bootstrap 의 adapter 의존을 `implementation` 으로)과 알림 표시
  - #53: `paths()` 로 WSL 경로 확인
  - M5: 설정 화면에서 `save` 를 쓴다
