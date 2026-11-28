# 0020. Claude 대화 기록 읽기: 앞부분만 · 메모리에만 · 모르면 끄기

- 상태: 채택(#208, 2026-11-28 · #216 "시작 명령" 절 추가)
- 날짜: 2026-11-28
- 관련: #208 · #207 · #216, FR-TK-09 · NFR-06, SDD §7.3 · §13.2, ADR-0002

## 맥락
FR-TK-09 는 Claude Code 를 쓴 폴더 아래에 이전 대화(첫 메시지 요약 · 마지막 시각)를 두고, 고르면 `claude --resume <세션ID>` 로 이어 가게 한다. 근거가 되는 기록은 각 환경의 `~/.claude/projects/<인코딩된 경로>/<세션ID>.jsonl` 이다.

- 형식은 **공개 사양이 아니다**. Claude Code 판이 바뀌면 줄 종류와 키가 늘거나 바뀐다.
- 폴더 이름의 인코딩은 되돌릴 수 없다(`/` · `.` → `-`, 한글 경로 → `-----`). 경로는 파일 안의 `cwd` 로만 알 수 있다.
- 파일은 크다. 이 PC 의 기록 55개 중 49개가 256KB 를 넘고, 가장 큰 것은 150MB 를 넘는다. 한 줄이 400KB 인 경우도 있다.
- 대화 내용은 개인 정보다(NFR-06). 비밀값 · 코드 · 경로가 섞여 있다.

**실제 파일에서 확인한 구조**(2026-11-28, WSL 의 기록 55개 — 키와 줄 종류만 보고 내용은 보지 않았다)
- 한 줄이 JSON 객체 하나다. 줄 종류(`type`)는 `mode` · `permission-mode` · `atis-latch` · `session-owner` · `file-history-snapshot` · `user` · `assistant` · `attachment` · `system` · `api-request` · `api-request-blob` · `last-prompt` · `ai-title` 등 20가지가 넘는다. 깨진 줄(잘린 마지막 줄 등)도 있다.
- `cwd` 는 `user` · `assistant` · `attachment` 같은 대화 줄에 있고, 앞의 상태 줄(`mode` 등)에는 없다. 모든 파일에서 3~7번째 줄에 처음 나온다. 옛 형식은 첫 줄이 `summary` 다.
- `user` 줄의 `message.content` 는 문자열 또는 블록 배열이다. 블록 배열은 대부분 `tool_result`(도구 결과)이고, 사람이 쓴 것은 `text`(+`image`) 블록이다.
- 사람이 쓰지 않은 `user` 줄: `isMeta: true`, 그리고 여는 태그로 시작하는 문자열(`<command-name>` · `<local-command-stdout>` · `<local-command-caveat>` · `<task-notification>` · `<system-reminder>` · `<bash-input>` 등).
- `api-request-blob` 줄의 `message.role` 도 `user` 다 — `type` 으로 걸러야 한다.
- 대화 파일과 같은 폴더의 `<세션ID>/subagents/agent-*.jsonl` 은 서브에이전트 기록이다(대화가 아니다).
- 진짜 첫 사용자 메시지는 대부분 앞 5KB 안에 있으나, 55개 중 3개는 앞의 큰 줄 때문에 256KB 를 넘어서 있다.

## 선택지
**얼마나 읽나**
1. 파일 전체를 읽는다 — 요약 · 마지막 메시지 시각까지 정확하다. 그러나 150MB 파일을 시작 때마다 읽고, 필요 없는 개인 정보까지 메모리에 올린다.
2. **앞부분만(200줄 또는 256KB)** — cwd 는 언제나 찾고, 요약은 대부분 찾는다. 못 찾으면 제목 없이 보인다.
3. `ai-title` · `last-prompt` 줄을 쓴다 — 요약이 더 좋을 수 있으나 파일 끝 쪽에 있고, 판마다 있는지 모른다.

**형식을 모를 때**
1. 예외로 시작을 막는다 — 선택 기능 때문에 앱이 안 뜬다.
2. **환경 단위로 기능을 끄고 경고만 남긴다** — 파일 하나가 틀리면 건너뛰고 센다.

## 결정
- port.out `ConversationSource`(SPI, `ServiceLoader` 로 여럿)와 구현 `ClaudeProjectsImporter`(`app/adapter/toolkit/claude`). 결과는 `Result<ConversationScan, ConversationError>` — `NoHistory`(폴더 없음) · `UnknownFormat`(파일은 있는데 어디서도 cwd 를 못 찾음) · `Unreadable`(목록 · 파일을 못 읽음). err 이면 부르는 쪽이 그 환경의 대화 기능을 끈다.
- **형식 의존 범위**는 이것뿐이다: `projects/*/*.jsonl`(하위 폴더는 안 봄) · 파일 이름 = 세션 ID · 줄마다 `cwd` · `type` · `isMeta` · `message.content`(문자열 또는 `text`/`tool_result` 블록). 그 밖의 키와 줄 종류는 모른 체한다.
- **읽는 상한**: 파일마다 앞에서 200줄 또는 256KB(밑 스트림에서 읽는 바이트로). cwd 와 첫 사용자 메시지를 둘 다 찾으면 바로 멈춘다. 상한에 걸친 줄은 JSON 이 깨져 버려진다.
- **첫 사용자 메시지**: `type: "user"` · `isMeta` 아님 · `tool_result` 블록 없음 · 여는 태그(`<[a-z][a-z0-9_-]*>`)로 시작하지 않음. 배열이면 `text` 블록을 이어 붙인다. 태그 목록을 하나하나 적지 않고 모양으로 거른다 — 새 명령 태그가 생겨도 요약으로 새지 않는다. 대가로 사용자가 `<div>` 로 시작한 메시지도 건너뛴다(그다음 메시지가 요약이 된다).
- **요약**(`PastConversation.summarize`, domain): 공백류를 하나로 접고, 제어 · 서식 문자를 지우고, 80 코드 포인트로 자른다.
- **세션 ID** 는 `[A-Za-z0-9_-]{1,128}` 만 받는다(`ConversationId`). 명령줄(`claude --resume <id>`)에 그대로 들어가기 때문이다. 셸 입력 줄은 `ShellLine`(1~512 코드 포인트, 제어 문자 없음 — Enter 가 끼면 실행돼 버린다).
- **개인 정보**: 요약은 메모리에만 둔다(`state.json` 에 쓰지 않는다). 로그는 파일 수 · 대화 수 · 건너뛴 수 · 걸린 시간뿐이고, 내용 · 요약 · 예외 메시지는 남기지 않는다. 사용자 파일은 쓰지 않는다.
- `lastActive` 는 파일 수정 시각이다 — 마지막 줄까지 읽지 않고 알 수 있다.
- `VisitSource`(#207 의 이력 출처)는 이 PR 이 먼저 만든다. #207 은 같은 모양을 그대로 쓴다.

## 결과
- 시작 때 기록이 많아도 파일당 256KB 안에서 끝난다. WSL 기록은 `\\wsl.localhost\…` 로 읽으므로 그 배포판을 깨운다 — 언제 부를지는 부르는 쪽(유스케이스) 몫이다.
- 형식이 바뀌면 `UnknownFormat` 으로 기능이 꺼질 뿐 앱은 뜬다. 규칙은 실제 파일 구조를 본뜬 합성 픽스처(`command-then-message.jsonl`)로 고정했다 — 형식이 바뀌면 이 픽스처와 이 ADR 을 같이 고친다.
- 요약을 못 찾는 대화(앞부분이 큰 줄로 채워진 경우)는 제목 없이 시각만 보인다.
- 후속: 가져오기 유스케이스(이력 합치기 #207), 트리에 대화 그리기 · 이어서 하기(UI), 파일 쓰기 금지 ArchUnit 규칙.

## 개정: 시작 명령 (#216, 2026-11-28)
대화를 고르면 그 폴더에서 같은 환경의 기본 셸로 새 창을 열고 `claude --resume <id>`(폴더 메뉴는 `claude --continue`)를 입력 · 실행한다(SDD §7.3 · §8.4). 셸이 rc · 프로필을 읽는 동안 쓴 글자는 rc 의 `read` 에 먹히거나 화면에 두 번 보이고, 경로가 사라져 홈에서 떴다면(#179) 다른 폴더에서 대화를 찾게 된다.

**결정**
- `ResumeConversationUseCase`(port.in) · `ConversationResumer`. 명령은 그 출처의 `ConversationSource.resume(id)` · `continueLatest()` 가 정한다 — application 은 `claude` 를 모른다. 공급자가 없는 출처면 창을 열지 않고 `OpenError.UnknownSource`.
- 시작 명령은 **메모리 표**(`StartupCommands`, 창 ID → (요청한 폴더, `ShellLine`))에만 있다. `PaneSpec` · `state.json` 에 넣지 않으므로 복원 때 다시 실행하지 않는다. 창을 여는 상태 전이 안에서(`LayoutChanged` 를 내기 전에) 단다 — UI 는 그 이벤트를 받고서야 셸을 띄우므로, 셸이 명령보다 먼저 뜨는 경쟁이 없다.
- 그 창의 **첫 셸**(셸 선택 화면이 떴으면 사용자가 고른 셸)이 뜰 때 한 번 꺼내 그 셸에만 단다. `restart` 에는 달지 않는다.
  - 셸 통합이 경로를 알려 주면(통합 설치됨 · 모드가 NONE 아님) **첫 cwd 보고**(= 첫 프롬프트, ADR-0003 · 0015)를 기다린다. cwd 추적이 기록하는 `RecordVisitUseCase` 를 감싸 첫 보고를 본다 — OSC 를 두 번 파싱하지 않고, ssh host 고정 같은 추적 규칙을 그대로 따른다. 보고한 경로가 요청한 폴더와 같으면(`WorkspacePath` 비교) `line + "\r"` 을 한 번 쓰고, 다르면 쓰지 않는다(경로가 다름).
  - 추적하지 않으면(통합 없음 · cmd 처럼 모드 NONE) 띄운 경로가 그 폴더일 때 1.5초 뒤에 쓴다. 홈에서 떴으면 쓰지 않는다(경로가 다름).
  - 첫 보고가 10초 안에 없으면 쓰지 않는다(시간 초과). 셸이 먼저 끝나면 쓰지 않는다(셸이 끝남) — 창을 닫아 끝났으면 알리지 않는다.
  - 결정은 한 번뿐이다(셸마다 모니터 하나로 지키는 상태, java-style 8 의 "출력 포트 일정 조율" 예외). 정해지면 남은 예약을 거둔다.
- 쓰기는 PTY 읽기 스레드가 아니라 I/O 가상 스레드에서 한다(`TerminalStream.write` 는 블로킹될 수 있다). 예약은 저장 타이머(`Scheduler`)를 같이 쓴다 — 일이 짧다.
- 건너뛴 이유는 `StartNotice.StartupCommandSkipped(line, reason)` 이다. 세션이 뜬 뒤에 정해지므로 새 이벤트 `AppEvent.SessionNotice(pane, notice)` 로 내고, UI 는 상태줄 알림 한 줄로 보인다(i18n 키 `start.command.*`).
- **셸별 따옴표**: 명령줄에 들어가는 값은 `ConversationId`(`[A-Za-z0-9_-]{1,128}`)뿐이고 이 글자들은 bash/zsh · PowerShell 5.1 · cmd 어디서도 특수 문자가 아니다. 그래서 셸마다 따옴표를 따로 씌우지 않고 `ShellLine` 을 그대로 쓴다. 공급자가 ID 외의 값(경로 등)을 명령에 넣게 되면 셸 종류별 인용을 이 절에 더한다.

**결과**
- 경로가 사라졌거나 프로필이 느린 셸에서는 명령이 실행되지 않고 상태줄에 이유가 보인다 — 사용자가 직접 입력하면 된다.
- 셸 통합이 없는 셸은 1.5초가 rc 보다 짧으면 입력이 먹힐 수 있다. 통합이 있는 셸(기본)은 영향이 없다.
- 시작 명령이 달린 창을 셸을 띄우기 전에 닫으면 표에 항목이 남는다. 창 번호는 늘기만 하므로(`Workspace.nextPane`) 다른 창에 잘못 쓰이지 않는다.
