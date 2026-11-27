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
  - `tanager.domain.session.WorkingDir`(sealed: `Home` | `At(WorkspacePath)`)를 둔다.
  - `SessionState` 의 `Starting.dir` · `Running.cwd` · `Exited.lastCwd` 는 `WorkingDir` 가 된다.
  - `TerminalSession.cwdChanged(WorkspacePath)` 는 `At` 으로 바꾼다.
  - `PlatformModule.launchCommand(ShellProfile, WorkingDir)` 로 바꾼다(ADR-0007 개정).
    - 개정: ADR-0014(#55)에서 셸 통합 결과를 세 번째 인자로 받는 `launchCommand(ShellProfile, WorkingDir, Integration)` 가 됐다.
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
  - 파일이 없으면 오류가 아니라 기본값이다. 비었거나 공백뿐인 파일도 없는 파일과 같다.
  - `schemaVersion` 이 없으면 1 로 본다. 1 보다 크면 읽을 수 있는 만큼 읽고 경고를 남긴다. 그 파일은 읽기 전용이다.
- **config.json 쓰기**(`save`)
  - 디스크의 파일을 다시 읽는다 → 아는 키(`schemaVersion` · `defaultProfile` · `profiles.<id>.startDir`)만 덮어쓴다 → `config.json.tmp` 에 쓴다 → `ATOMIC_MOVE` + `REPLACE_EXISTING` 으로 교체한다.
  - 디스크의 파일이 손상됐거나 상위 버전이면 쓰지 않고 오류를 돌려준다. `schemaVersion` 을 정수 1 로 해석할 수 없을 때(문자열 · 소수 · int 범위 밖 등)도 같다. 손상 파일은 절대 덮어쓰지 않는다.
  - 주석은 저장하면 사라진다. UTF-8 BOM 도 사라진다(BOM 없이 쓴다). 그래서 **M2 는 config 를 자동으로 저장하지 않는다**.
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

## 개정 (#70, 2026-11-26) — 기본 프로파일은 사용자가 고르고, 고를 때만 저장한다

### 맥락
사용자 지적(v0.2.0 릴리스 PR #69): 셸이 자동으로 정해지고(첫 WSL → 첫 항목), 바꾸려면 config.json 을 손으로 고쳐야 한다.
FR-SES-02 "기본 프로파일 지정"이 사용자에게는 없는 셈이다.

### 바뀌는 결정
- **대체 규칙을 없앤다.** 기본 프로파일이 없거나(첫 실행) 탐지 목록에 없으면(배포판 삭제) 셸을 띄우지 않는다. 창 안에 셸 선택 화면을 띄운다.
  - 사라졌으면 "기억한 셸 <id> 를 찾을 수 없다"를 함께 보인다.
  - domain `DefaultProfile` · `ProfileChoice` 와 `StartNotice.ProfileReplaced` 를 지운다. 그 자리에 `ProfileResolution`(Found | Choose | NoShell)을 둔다.
  - `StartSessionUseCase.start` 는 이때 `StartFailure.ChoiceRequired` 를 돌려준다.
- **"M2 는 config 를 자동으로 저장하지 않는다"를 다음으로 바꾼다.** 앱이 스스로 판단해 저장하지 않는다.
  사용자가 선택 화면에서 "다음부터 이 셸로 바로 열기"를 켜고 셸을 골라 **그 셸이 떴을 때만** `defaultProfile` 을 저장한다(`RememberDefaultProfileUseCase`).
  - 체크박스 기본값: 시작 때 선택 화면에서 켜짐, 종료 띠의 Tab 으로 연 선택 화면에서 꺼짐.
  - 절차
    - `load` 가 실패하면 저장하지 않는다. 기본값으로 덮어쓰면 `profiles.<id>.startDir` 이 지워진다.
    - 이미 같은 값이면 쓰지 않는다. 주석과 BOM 이 그대로 남는다.
    - 그 밖에는 읽은 값에서 `defaultProfile` 만 바꿔 `save` 한다. `save` 는 디스크를 다시 읽어 모르는 키를 보존하고, 손상 · 상위 버전 · 해석할 수 없는 schemaVersion 이면 거부한다.
  - 저장하지 못하면 셸은 그대로 쓰고 "기본 셸 <id> 을 저장하지 못했다 — <이유>"를 알린다.
- 탐지 목록에 없는 프로파일을 직접 요청해도 같다. 대체하지 않고 선택이 필요하다고 답한다.

### 결과
- 처음 실행하면 셸을 고르기 전까지 셸 프로세스가 뜨지 않는다. NFR-02(첫 창 2초)는 창과 선택 화면까지로 잰다.
- 기억하며 저장하면 그 파일의 주석 · BOM 이 사라진다(기존 한계). 형식이 틀려 읽을 때 무시한 아는 키도 지워진다.
  예: 문자열이 아닌 `startDir`, 객체가 아닌 `profiles`. 읽을 때 이미 경고로 알렸다.
- 배포판을 지웠다 다시 설치해도 기억한 id 가 같으면 그대로 다시 맞는다(기존 장점 유지). 그사이 시작할 때는 선택 화면이 뜬다.
- SDD §9.3 의 `defaultProfile` 주석을 "없거나 사라졌으면 시작할 때 선택 화면(FR-SES-02)"으로 바꾼다. schemaVersion 은 1 그대로다(키 뜻만 바뀐다).
- 설정 화면(M5)도 같은 `RememberDefaultProfileUseCase` 를 쓴다.

## 개정 (#182, 2026-11-28) — 설정 항목 확장과 저장하면 즉시 반영

### 맥락
`AppConfig` 는 `defaultProfile` · `profiles.<id>.startDir` · `openPlacement` 만 알았다. SDD §9.3 의 `appearance` · `terminal` · `history` · `monitor` · `keys` 는 모르는 키였다.
설정을 바꾸려면 파일을 고친 뒤 다시 시작해야 했다(FR-CFG-03 "저장하면 즉시 반영"이 없었다).

### 바뀌는 결정
- **읽는 키를 늘린다.** port.out 값 타입 `AppearanceConfig` · `TerminalConfig` · `HistoryConfig` · `MonitorConfig` · `KeyConfig` 를 `AppConfig` 에 더한다. application 은 Jackson 을 모른다.
  - 기본값은 SDD §9.3 그대로다. `appearance.cornerRadius` 는 읽지 않는다(모서리는 테마 토큰이 정한다, ADR-0010).
  - 범위: `terminal.size` 8~32 · `lineHeight` 1.0~2.0 · `scrollback` 0~100000, `history.maxEntries` 100~100000, `monitor.intervalSec` 1~10 · 임계값 0 초과 1 이하 · `cpuSustainSec` ≥ 1. 값 타입의 compact constructor 가 지킨다.
  - 읽는 규칙은 지금과 같다. 형식이 틀린 키는 그 키만 기본값으로 두고 `IgnoredValue` 경고를 남긴다.
    - 범위 밖 숫자는 가까운 경계로 고정하고 `ConfigWarning.OutOfRange(key, found, used)` 를 남긴다. 아래 경계가 열린 임계값(0 이하)은 고정할 값이 없어 기본값을 쓴다.
    - 배열 · 객체 안의 틀린 항목(`monitor.items` 의 `"GPU"`, 빈 glob, 빈 바인딩)은 그 항목만 뺀다. 키 이름은 `monitor.items[1]` · `keys.bindings.<id>` 처럼 쓴다.
    - 이름 값(`theme` · `animations` · `monitor.items`)은 대소문자를 가리지 않는다. `openPlacement` 는 지금처럼 대문자만 받는다.
    - `keys.prefix` 가 `null` 이거나 비었으면 prefix 모드를 끈다(경고 없음). `history.exclude` 는 glob 원문이고 해석은 domain 이 한다.
  - 필드를 더하는 것이라 schemaVersion 은 1 그대로다(SDD §9.4).
- **`save` 가 쓰는 키는 늘리지 않는다.** 지금처럼 `schemaVersion` · `defaultProfile` · `profiles.<id>.startDir` 만 덮어쓴다. 새 항목은 읽기만 하고, 파일의 값을 그대로 보존한다. 저장하면 주석이 사라지기 때문이다.
  - 계약(`ConfigRepositoryContract`)에 "save 는 기본 프로파일 · 시작 경로만 쓰고 나머지 항목은 저장소의 값을 그대로 둔다"를 더했다. 메모리 Fake 도 같게 동작한다.
- **설정 파일 열기**: `ConfigRepository.ensureFile()` 이 파일이 없을 때만 `{"schemaVersion": 1}` 을 `CREATE_NEW` 로 만든다(우리 파일이라 써도 된다). 있으면(빈 파일 · 손상 파일 포함) 바이트를 건드리지 않는다.
  - port.in `SettingsUseCase.file()` 은 `Result<Path, ConfigError>` 를 돌려준다. 폴더를 만들 수 없으면 UI 가 이유를 보여야 하기 때문이다.
- **감시**: port.out `ConfigWatch.watch(Runnable)` → `Subscription`. persistence 의 `WatchServiceConfigWatch` 가 appData 폴더를 `WatchService` 로 본다.
  - config.json 의 만들기 · 수정 이벤트만 본다. 편집기의 원자적 저장(임시 파일 → 이름 바꾸기)은 만들기로 온다. 넘침(OVERFLOW)도 바뀐 것으로 본다.
  - 마지막 관련 이벤트 뒤 300ms 조용하면 한 번 알린다. 같은 폴더의 다른 파일(state.json 등) 이벤트는 기한을 늘리지 않는다.
  - 구독마다 가상 스레드 하나에서 기다리고 콜백도 거기서 부른다. 콜백이 던지면 남기고 계속 본다.
  - 감시를 걸 수 없으면(폴더를 만들 수 없음 등) 경고를 남기고 알리지 않는 구독을 돌려준다. 앱은 그대로 돈다.
  - 삭제는 보지 않는다. 지운 직후 기본값으로 바뀌지 않게 하려는 것이다. 다음에 파일이 생기면 반영된다.
- **다시 읽기**: application `SettingsReloader` 가 `load` 한 결과를 StateActor 명령으로 넘긴다. 읽기와 제출은 한 monitor 안에서 해서, 콜백이 겹쳐도 나중에 읽은 값이 나중에 반영된다.
  - `AppState.settings` 는 `Optional<AppConfig>` 다. 처음 읽기 전에는 비어 있다.
  - `SettingsLoaded(LoadedConfig)`: 값이 바뀌었을 때만 상태를 바꾸고 `AppEvent.SettingsChanged(config)` 를 낸다. 같은 값이면 이벤트가 없다. 다시 읽을 때의 경고는 `SettingsNotice(Warned)` 로 알린다.
  - `SettingsLoadFailed(ConfigError)`: 다시 읽다 실패하면(손상 · 읽기 실패) 마지막 정상 설정을 그대로 쓰고 `SettingsNotice(KeptPrevious)` — 상태줄에 "설정 파일을 읽지 못해 이전 설정을 쓴다"가 뜬다. 고쳐 저장하면 다시 반영된다.
  - load 가 예외를 던지면 `Unreadable` 로 바꿔 같은 길로 넘긴다. 감시 콜백은 던지지 않는다.
- **시작도 같은 길**: bootstrap 은 UI 가 이벤트를 구독한 뒤 `Assembly.startSettings()` 를 부른다. 감시를 건 뒤 한 번 읽으므로 `SettingsChanged` 가 한 번 나간다.
  - 처음 읽을 때는 값이 기본값과 같아도 늘 낸다. UI 는 이 이벤트만 보면 되고, bootstrap 이 UI 에 설정을 따로 넘기지 않는다.
  - 처음 읽기가 실패하면 기본값으로 `SettingsChanged` 를 낸다. 처음 읽을 때의 경고 · 오류는 알리지 않는다 — 셸 시작 알림(`StartNotice.ConfigWarned` · `ConfigUnreadable`)이 이미 보인다.
  - 종료할 때 감시 구독을 먼저 끊는다.

### 결과
- 설정 파일을 저장하면 1초 안에 `SettingsChanged` 가 한 번 나간다. 새 값을 화면에 적용하는 일(테마 · 글꼴 · 키 · 리소스 · 이력 한도)은 각 UI · 리소스 이슈가 이 이벤트를 받아 한다. 그 이슈들은 bootstrap 을 건드리지 않아도 된다.
- 셸 시작 · 창 열기는 지금처럼 그때 config 를 다시 읽는다(`SessionStarter` · `PaneManager`). 상태의 설정을 쓰도록 바꾸는 일은 필요할 때 한다.
- macOS 의 기본 `WatchService` 는 폴링(약 10초)이라 1초 안에 반영되지 않을 수 있다. macOS 플랫폼(M6)에서 다시 본다.
