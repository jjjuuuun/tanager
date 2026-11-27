# 0013. 이력 저장: state.json 의 history · 저장 시점 · 손상 처리

- 상태: 채택(#78, 2026-11-26) · 개정(#79, #178, #179) — 형식 · 원자적 쓰기 · `.bak` · 손상 · 상위 버전 · 잠금은 #78 에서, 저장 시점(throttle) · 불러오기 전 방문 · 종료 시 저장은 #79(2026-11-27)에서 구현했다.
- 날짜: 2026-11-26
- 관련: #78 · #79 · #178 · #179, FR-HIS-01 · FR-RES-01 · FR-RES-02 · FR-RES-03 · FR-RES-04, NFR-02 · NFR-03, SDD §6.2 · §6.4 · §9.1 · §9.2 · §9.4, ADR-0009 · 0012

## 맥락
SDD 는 이력 · 작업 공간 · 창을 한 파일(`state.json`)에 둔다. 작업 공간 · 창은 M5 에서 쓴다. M3 는 이력만 먼저 저장해야 한다. 재시작하면 이력이 사라질 경우 사이드바 트리가 쓸모없기 때문이다.

이력을 잃는 길이 여럿이다(M3-R7).
- 불러오기 전에 빈 이력을 저장한다.
- 두 인스턴스가 서로 덮어쓴다.
- 손상된 파일을 덮어쓴다.
- v0.5 가 쓴 파일(`workspace` 포함)을 v0.3 이 열어 저장하면서 `workspace` 를 지운다.

## 선택지
- (a) `history.json` 을 따로 두고 M5 에 `state.json` 을 만든다. M5 에 파일 이전(마이그레이션)과 SDD 수정을 부른다.
- (b) **`state.json` 의 `history` 키만 지금 쓴다.**

## 결정
**(b)**. port.out `HistoryRepository`(`load` · `save`)를 adapter/persistence 의 `JsonStateRepository` 가 구현한다. Jackson 3 트리 모델을 쓴다(config 와 같은 라이브러리, 새 의존성 없음).

- **형식**(schemaVersion 1, SDD §9.2 그대로)
  - `{"schemaVersion":1,"history":{"entries":[{"env":{"kind":"wsl","distro":"U"}|{"kind":"windows"},"path":"…","firstVisited":"ISO-8601 UTC","lastVisited":"…","visitCount":n,"pinned":b,"alias":s|null,"hidden":b,"sources":["shell"]}]}}`
  - 시각은 `Instant.toString()`. UTF-8 · BOM 없음 · 한글은 이스케이프하지 않는다. 줄바꿈은 OS 와 상관없이 LF(config 와 같은 PrettyPrinter).
  - `sources` 는 load 때 항목별 배열을 `WorkspacePath` 키로 보관했다가 저장할 때 그대로 다시 쓴다. 새 항목은 `["shell"]` 이다. 대소문자만 다른 Windows 경로처럼 합쳐지는 항목은 배열을 합친다(먼저 나온 순서, 겹치는 값은 한 번). `sources` 가 없거나 배열이 아닌 쪽은 저장할 때처럼 `["shell"]` 로 보고 합친다 — 없는 쪽을 빼면 shell 출처를 잃는다. M6 의 `claude-code` 는 값 추가라 버전이 그대로다. 이 앱이 쓰지 않는 값이라도 지우면 이후 버전이 넣은 출처를 잃는다.
- **모르는 최상위 키 보존**: load 때 읽은 최상위 키 가운데 `schemaVersion` · `history` 를 뺀 나머지를 adapter 가 들고 있다가 저장할 때 그대로 다시 쓴다. 디스크를 다시 읽지 않는다. 사람이 고치는 파일이 아니어서 config(ADR-0009, 저장 때 디스크를 다시 읽음)와 판단이 다르다. 항목 안의 모르는 필드는 보존하지 않는다(받아들인 한계).
- **항목 읽기**: 해석할 수 없는 값이 있는 항목만 건너뛰고 `EntriesSkipped(n)` 으로 알린다 — 객체가 아닌 항목, 필수 필드 누락, 상대 경로 등 경로 오류, 모르는 `env.kind` · `distro` 없는 `wsl`, 시각 형식 오류, `visitCount` 가 1 이상의 정수가 아님, `pinned` · `hidden` 이 불리언이 아님. 고칠 수 있는 위반은 고쳐서 읽는다 — 고정이면서 숨김이면 고정을 남기고 숨김을 푼다(도메인 `pin` 과 같은 우선순위), 처음 > 마지막이면 처음을 마지막으로 맞춘다, 틀린 별칭(문자열이 아님 · `Alias` 불변식 위반)은 별칭만 버린다. 값 하나 때문에 경로와 방문 기록을 통째로 잃지 않으려는 것이다. 고친 항목 수는 알림으로 주지 않고 WARNING 로그만 남긴다. 사용자가 할 일이 없고, 고친 결과는 트리에 그대로 보이기 때문이다. 대소문자만 다른 Windows 경로 같은 중복은 `WorkHistory.restore` 가 합친다.
- **`schemaVersion` 판정**: 정수 값이면 형식과 범위에 상관없이 버전이다 — Jackson 의 `canConvertToExactIntegral`(정수 노드는 `isIntegralNumber`, 소수는 소수점 아래가 0)로 보고 `canConvertToLong` 이면 그 값을 쓴다. int 를 넘는 정수(`3000000000`)나 `2.0` 도 1 보다 크면 상위 버전이다. long 을 넘는 양수는 `NewerSchema(Long.MAX_VALUE)` 로 알린다(그래서 `NewerSchema.version` 은 `long`). `1.0` 은 1 이다. int 로만 판정하면 이런 새 버전 파일을 손상으로 보고 옮긴다.
- **뼈대 손상**: JSON 문법 오류, 최상위가 객체가 아님, `schemaVersion` 이 1 이상의 정수 값이 아님, `history` 가 객체가 아님, `history.entries` 가 배열이 아님. 이때는 파일 전체를 믿지 않는다. 단 `schemaVersion` 이 1 보다 큰 정수 값이면 뼈대 검사를 하지 않는다(아래 읽기 전용).
  1. 먼저 `state.json.bak` 을 읽는다. 읽다 **입출력 오류**(권한 · 같은 이름의 폴더 등)가 나면 잠금을 쥐었는지와 상관없이 "없음"으로 보지 않고 아무것도 옮기지 않은 채 `Io(".bak 을 읽지 못했다 …")` 로 끝낸다. 정상인지 알 수 없는 백업을 두고 원본을 옮긴 뒤 빈 이력을 쓰기 가능으로 열면, 첫 저장이 그 백업을 덮어쓸 수 있다.
  2. 손상 원본을 옆에 둘 이름은 `state.json.corrupt-yyyyMMdd'T'HHmmss'Z'`(UTC)다. 같은 초의 이름이 이미 있으면 `-1`, `-2` … 를 붙이고, 있던 파일은 건드리지 않는다.
  3. 잠금을 쥐었고 `.bak` 이 정상이면 **(1) 손상 원본을 그 이름으로 복사하고 (2) `state.json` 을 `.bak` 바이트로 원자적으로 바꾼 뒤** 그 내용을 쓴다(`RecoveredFromBackup(복사한 파일명)`). 어느 단계에서 멈춰도(전원 차단 포함) `state.json` 은 손상 원본이거나 복구한 내용이다 — **"`state.json` 없음 + 정상 `.bak`" 은 생기지 않는다.** 그 상태면 저장 없이 끝난 다음 실행이 알림 없이 빈 이력을 열고, 그 다음 실행의 첫 저장이 좋은 `.bak` 을 덮어쓴다.
     - (1) 이 실패하면 `Io` 이고 아무것도 바뀌지 않는다.
     - (2) 가 실패하면 `Io` 다. 손상 원본은 제자리에 그대로 있고 복사본은 해가 없이 남는다(메시지에 적는다). 다음 load 가 복구를 다시 시도한다.
     - 처음에는 원본을 옮긴 뒤 되돌려 쓰고, 실패하면 제자리로 돌려 놓았다. 옮기기와 쓰기 사이에 멈추거나 둘 다 실패하면 위 상태가 남아 복사 순서로 바꿨다.
  4. 잠금을 쥐었고 `.bak` 도 손상이거나 없으면 손상 원본을 그 이름으로 **옮기고** 빈 이력으로 시작한다(`CorruptMovedAside(파일명)`). 옮기지 못하면 `Io` 오류다(첫 저장이 원본을 덮어쓰게 두지 않는다).
  5. 잠금을 쥐지 않았으면(다른 인스턴스 · 잠금 파일 열기 실패) 아무것도 복사 · 옮기거나 되돌려 쓰지 않는다. `.bak` 이 정상이면 `RecoveredFromBackup(empty)` 로 읽고, 아니면 빈 이력이다. 어느 쪽이든 읽기 전용이다.
     - **`.bak` 도 없거나 손상이면 손상 알림을 주지 않는다.** 알림은 잠금 쪽(`LockedByOtherInstance` · `LockUnavailable`) 하나뿐이고 손상은 WARNING 로그에만 남는다. 파일을 건드리지 않았고 읽기 전용이라 사용자가 할 일이 없으며, 잠금을 쥔 인스턴스가 열 때 다시 판정한다. 손상 알림(`CorruptNotMoved` 같은)을 더하는 것은 알림 목록이 바뀌므로 이번에는 하지 않는다.
  6. 손상으로 판정한 파일은 그 실행의 `.bak` 으로 복사하지 않는다. 좋은 백업을 손상 파일로 덮지 않으려는 것이다. 이를 위해 "이번 실행의 `.bak` 은 정해졌다" 표시는 원본을 실제로 옆에 두었거나(복사 · 옮기기) `.bak` 으로 복구했을 때만 세운다. 잠금이 없어 아무것도 못 한 load 뒤 쓰기 가능으로 다시 읽으면, 첫 저장이 그때의 `state.json` 으로 `.bak` 을 만든다.
- **읽기 전용**: 다음 경우 읽을 수 있는 만큼 읽고 `READ_ONLY` 로 연다. `save` 는 파일을 건드리지 않고 `ReadOnly` 오류다. 기록은 메모리에만 남는다.
  - `schemaVersion > 1`(`NewerSchema(v)`, SDD §9.4 다운그레이드 보호). 새 버전은 `history` 모양을 바꿀 수 있으므로 뼈대 검사를 건너뛰고, `history` 가 객체가 아니거나 `entries` 가 배열이 아니면 항목 0개로 읽는다. 손상으로 보지 않으므로 `.bak` 으로 바꾸거나 옆으로 옮기지 않는다.
  - 다른 인스턴스가 `state.lock` 을 쥐고 있다(`LockedByOtherInstance`). load 에서 `FileChannel.tryLock` 으로 잡고 `close()` 에서 푼다. 같은 JVM 의 다른 채널이 쥔 경우(`OverlappingFileLockException`)도 같게 본다. 다른 인스턴스가 쓰는 손상 파일은 옆으로 옮기지 않는다.
  - `state.lock` 을 열 수 없다(`LockUnavailable(detail)`, 권한 · 같은 이름의 폴더 등). 다른 인스턴스와 겹치는지 알 수 없으니 쓰지 않지만, 이력을 못 보는 것보다 나으므로 `Io` 오류로 끝내지 않는다.
  - 포트 계약(`HistoryRepository.save` Javadoc): 마지막 load 가 성공해 `WRITABLE` 로 열었을 때만 쓴다. load 전("불러오기 전에는 쓰지 않는다") · `READ_ONLY` · 마지막 load 실패 · close 뒤("닫힌 저장소에는 쓰지 않는다")의 `save` 는 `ReadOnly` 다. load 때 읽은 다른 최상위 키가 없어 `workspace` 를 지울 수 있기 때문이다. 메모리 Fake 도 마지막 load 의 access 를 기억해 같은 규칙을 따른다.
  - 단일 인스턴스(기존 창 앞으로 가져오기)는 M5 에서 다시 본다.
- **원자적 쓰기**: `state.json.tmp` 에 쓴 뒤 `ATOMIC_MOVE` + `REPLACE_EXISTING` 으로 바꾼다. 실패하면 `Io` 오류이고 기존 파일은 그대로다.
- **`.bak` 시점**: **실행(저장소 인스턴스)마다 첫 저장 직전에 한 번** 그때의 `state.json` 을 복사한다. 기존 파일이 없으면 만들지 않는다. SDD §6.4 는 "교체 전마다"였지만, 1초마다 갱신되는 `.bak` 은 같은 실행 안의 논리 오류를 막지 못한다(SDD 수정). 백업을 만들지 못해도 저장은 계속한다. 백업이 없다고 새 방문을 잃으면 안 되기 때문이다.
- **저장 시점**(#79 구현됨): **첫 미저장 변경 후 1초**(throttle). 트레일링 디바운스는 변경이 계속 오면 저장이 계속 밀려 FR-RES-01 "1초 안"을 지키지 못한다. 저장은 I/O 가상 스레드에서 한 번에 하나만 돈다. 종료 때는 바로 flush 한다.
  - `HistorySaver`(application.service)가 `HistoryChanged` 를 받아 예약이 없을 때만 1초 뒤로 예약한다(port.out `Scheduler`). 예약이 돌 때 그 시점의 최신 이력을 쓴다.
  - 저장 중에 바뀌면 그 변경의 예약은 그대로 돌고, 저장 중에 기한이 오면 저장이 끝나자마자 다음 저장을 한다. 동시에 두 저장이 돌지 않는다.
  - 마지막으로 저장했거나 불러온 이력과 같으면 쓰지 않는다. 불러오기만 하고 방문이 없으면 종료 때도 파일을 건드리지 않는다.
  - 저장하는 동안의 변경은 **쓰는 중인 이력**(`writing`)과 비교한다(쓰는 중이 아니면 위의 이력). 저장 중에 파일에 있던 값으로 되돌린 변경(고정했다가 풀기 등)을 "같다"고 버리면, 저장이 끝난 뒤 파일과 메모리가 어긋난다.
  - 실패하면 자동으로 재시도하지 않고 다음 변경 때 다시 시도한다. `StoreNotice` 로 상태줄에 알리되 직전과 같은 오류가 이어지면 생략하고, 성공하면 이 기억을 지운다.
- **불러오기 전 방문**(#79 구현됨): `Loading(pending)` 상태에서 쌓아 두었다가 load 결과 위에 다시 적용한다. Loading 중에는 저장하지 않는다.
  - `AppState.store = Loading(pending) | Writable | ReadOnly(reason)`. 앱 시작은 `Loading([])` 이다.
  - Loading 중 방문(`VisitRecorder`)은 메모리 이력에 넣어 트리에 바로 보이고, `PendingVisit(경로, 시각)` 으로도 쌓는다.
  - `HistoryLoaded` 명령이 불러온 이력 위에 pending 을 `recordVisit` 로 순서대로 다시 적용하고, `WRITABLE` 이면 Writable, `READ_ONLY` 면 `ReadOnly(첫 알림)` 이 된다. 불러오기 알림은 하나씩 `StoreNotice` 로 상태줄에 보인다.
  - `HistoryLoadFailed` 는 `ReadOnly(오류)` 로 바꾸고 알린다. 쌓인 방문은 메모리에 남는다.
  - `HistorySaver` 는 불러오는 동안 방문이 있었을 때만 불러온 뒤 저장한다(1초 throttle). 만들 때 이미 쌓여 있던 방문도 센다. 방문이 없었으면 불러온 이력은 파일과 같아 쓰지 않는다.
  - bootstrap 은 셸 탐지와 나란히 가상 스레드에서 load 한다. 저장소 인스턴스는 하나뿐이다(아래 POSIX 잠금).

## 결과
- FR-RES-01 · 04 의 이력 부분이 M3 에서 먼저 충족된다. M5 는 같은 adapter 가 `WorkspaceRepository` 도 구현해 같은 파일의 다른 키를 쓴다. 그때 "다른 최상위 키 보존"은 두 저장소가 한 문서를 나눠 쓰는 구조로 바뀐다.
- 계약은 `HistoryRepositoryContract` 로 고정한다. 메모리 Fake(`InMemoryHistoryRepository`)와 `JsonStateRepository` 가 함께 통과한다. 저장 거부 규칙(load 전 · `READ_ONLY` · load 실패 뒤 `save` → `ReadOnly`)도 계약에 있고, 구현마다 훅 `arrangeReadOnly()` · `arrangeLoadFailure()` 로 상황을 만든다(Json 은 상위 버전 픽스처 — #178 부터 v3 — · `state.json` 자리의 폴더). 손상 · 잠금 · 백업 · 형식은 `JsonStateRepositoryTest` 가 본다.
- `save` 는 블로킹 I/O 다. actor · EDT 에서 부르지 않는다(ADR-0012).
- POSIX(WSL · macOS)에서는 한 프로세스가 같은 파일에 연 채널 하나를 닫으면 그 프로세스의 잠금이 OS 수준에서 모두 풀린다. 한 프로세스에 저장소가 하나뿐이면 문제가 없다. 그래서 bootstrap 은 저장소를 하나만 만들고, **같은 프로세스에 같은 파일의 저장소 둘을 두는 것은 지원하지 않는다**. 같은 JVM 의 두 저장소 테스트는 JVM 잠금 표 수준만 검증하고, 프로세스 사이 잠금은 자식 JVM 이 `state.lock` 을 쥐는 테스트가 본다.

## 개정 (#79, 2026-11-27) — 저장기의 가변 상태
- java-style 규칙 8("가변 상태는 adapter 와 StateActor 안에만")에 예외를 하나 더한다: **출력 포트 일정 조율용 가변 상태는 단일 monitor 로 지켜 application.service 에 둘 수 있다**(`HistorySaver`).
- `HistorySaver` 의 예약 · 저장 중 여부 · 저장 중 기한 도래 · 미저장 표시 · 파일에 있다고 아는 이력 · 쓰는 중인 이력 · 직전 실패 · 불러오는 중 여부 · 불러오는 동안 방문 여부는 도메인 상태가 아니라 `HistoryRepository` · `Scheduler` 를 부르는 시점을 정하는 상태다. 이벤트 콜백(actor) · 예약(스케줄러) · 저장(I/O) · `flush`(종료) 네 스레드가 만지므로 모두 `monitor` 하나로 지키고, 블로킹 I/O(`save`)는 monitor 밖에서 한다.
- StateActor 에 넣지 않은 까닭: 저장 결과를 기다리는 동안 actor 를 막을 수 없고, 저장 시점은 도메인 · 트리에 보이는 상태가 아니다. adapter 에 두면 throttle 규칙(FR-RES-01 "1초 안")이 저장소 구현마다 갈라진다.
- 이 예외는 위 조건(출력 포트 호출 시점 조율 · monitor 하나 · I/O 는 monitor 밖)을 모두 만족할 때만 쓴다. 도메인 규칙을 담는 상태는 여전히 StateActor 의 명령으로 바꾼다.

## 개정 (#178, 2026-11-28) — state.json v2: 작업 공간 · 창 상태
- **버전**: `schemaVersion` 을 2 로 올린다. v1 문서(SDD §9.2 v1 예시)의 workspace 모양(문자열 `lastCwd`, 탭 `title`)과 v2 모양이 달라 의미가 바뀌기 때문이다(SDD §9.4).
  - 위 규칙의 "1 보다 큰 정수"는 이제 "2 보다 큰 정수"다 — 상위 버전(`NewerSchema`) · 뼈대 검사 생략 기준이 `schemaVersion > 2` 가 된다.
  - 대가: v0.3 · v0.4 는 v2 파일을 읽기 전용으로 연다(위 다운그레이드 보호). 이력은 보이지만 저장되지 않는다. v0.5 가 v1 파일에 처음 저장하기 직전의 v1 파일은 `.bak` 으로 남는다.
- **형식(v2)**: v1 에 다음을 더하고 바꾼다.
  - `workspace = {"activeTab":n,"tabs":[{"id":"t1","name":s|null,"focused":"p1","zoomed":"p1"|null,"layout":L}],"panes":{"p1":{"profile":s|null,"lastCwd":{"env":{…},"path":"…"}|null}}}`, `L = {"pane":"p1"} | {"split":"ROW"|"COLUMN","ratio":0.1~0.9,"first":L,"second":L}`.
  - 탭 `name` 이 `null` 이면 자동 이름(포커스 창 제목). `lastCwd` 는 이력 항목과 같은 `env` 표기를 써서 프로파일이 사라져도 경로의 환경을 안다.
  - `nextPane` · `nextTab` 은 저장하지 않고 읽을 때 쓰인 가장 큰 `p<n>` · `t<n>` 다음으로 계산한다(`Workspace.restore`). 재시작 전 창의 이벤트는 남아 있지 않으므로 닫은 번호를 다시 써도 된다.
  - `window = {"x","y","width","height","maximized","sidebar":{"visible","width"},"toolkit":{"visible","width"}}`. 좌표 넷이 모두 없으면 OS 가 정한 자리다. 빠진 필드는 기본값(`WindowState.DEFAULT`: 사이드바 236px · 도구함 264px, 둘 다 보임).
- **두 포트가 한 문서를 나눠 쓴다**: `JsonStateRepository` 가 `HistoryRepository` 와 `WorkspaceRepository`(`loadWorkspace` · `saveWorkspace` — 한 클래스가 둘을 구현해 `load` 와 시그니처가 겹치지 않게)를 함께 구현한다.
  - 읽기 · 잠금 · 손상 판정 · `.bak` 복구 · 마이그레이션은 문서 단위로 한 번 한다. 한 번 읽은 문서의 두 부분은 각각 한 번씩 꺼낸다 — `load` 와 `loadWorkspace` 가 어느 순서로 와도 파일을 한 번만 읽고, 이미 꺼낸 쪽을 다시 부르면 문서를 다시 읽는다. 문서 단위의 알림(복구 · 상위 버전 · 잠금)은 이력 쪽 `HistoryLoadNotice` 에만 온다.
  - 저장 거부 규칙은 두 포트가 같다(마지막 문서 읽기가 `WRITABLE` 일 때만). 어댑터가 두 부분의 마지막 값(읽었거나 저장에 성공한 값)을 들고 있다가 어느 쪽 save 든 문서 전체를 한 번에 원자적으로 쓴다(`synchronized`). 쓰기가 실패하면 그 부분의 마지막 값을 바꾸지 않는다.
  - "모르는 최상위 키 보존"은 `schemaVersion` · `history` · `workspace` · `window` 를 뺀 키에 적용한다. `window` 객체 안의 모르는 필드(예: M6 의 `toolkit.tab`)는 load 때 읽은 객체 위에 아는 필드만 덮어써 보존한다. workspace 안의 모르는 필드는 보존하지 않는다(이력 항목과 같은 한계). 작업 공간이 없으면 읽은 `window` 를 그대로 다시 쓴다.
- **마이그레이션 위치**: `Migration(from, to)` 는 JSON 트리 → JSON 트리 순수 함수이고 체인(`Migrations`)은 adapter/persistence 안에 둔다. Jackson 이 domain · application 에 들어가지 않게 하려는 것이다. 뼈대 검사 뒤, 해석 전에 돈다. `schemaVersion` 이 없으면 1 로 본다. 올렸으면 `Migrated(from, 2)` 를 알린다.
  - v1 → v2: history 는 그대로. 탭 `title` → `name`(이미 `name` 이 있으면 `title` 을 버림). 문자열 `lastCwd` 는 프로파일 ID 로 환경을 정해 `{env, path}` 로 바꾼다(`wsl:<배포판>` → WSL, `powershell` · `cmd` → Windows). 정할 수 없으면 workspace 만 버리고 `PartDiscarded("workspace", …)`. 그 밖의 모양은 그대로 두어 v2 읽기가 판정한다.
- **부분 손상**: workspace · window 의 값이 틀려도 문서 전체를 손상으로 보지 않는다(옆으로 옮기지 않고 `.bak` 으로 바꾸지 않는다). 이력은 그대로 읽는다.
  - 창의 명세(`profile` · `lastCwd`)가 틀렸거나 명세가 없거나 이미 다른 자리에 쓰인 창은 트리에서 빼고 형제로 축약한다. 틀린 분할 노드(모르는 방향 · 범위 밖 비율 · 자식 없음)는 그 아래 창과 함께 뺀다. 뺀 창 수를 `PanesDropped(n)` 으로 알린다.
  - 창이 모두 빠진 탭 · ID 가 없거나 겹치는 탭은 버린다. 남는 탭이 없으면 스냅샷은 비고 `PartDiscarded("workspace", …)`. workspace 가 객체가 아니거나 `tabs` 가 배열이 아니거나 `panes` 가 객체가 아니어도 같다.
  - 고칠 수 있는 값은 고친다: 포커스가 트리에 없으면 첫 창, 줌한 창이 없으면 줌 없음, `activeTab` 이 범위 밖이거나 그 탭이 버려졌으면 0, 틀린 탭 이름은 자동 이름.
  - window 의 값이 틀리면(객체가 아님 · 좌표 일부만 · 크기 < 1 · 폭 < 0 · 형식 오류) 창 상태만 기본값으로 하고 `PartDiscarded("window", …)`.
- **뼈대 손상 · `.bak` 복구**(FR-RES-04)는 위 규칙 그대로 문서 단위로 한다. `.bak` 으로 복구하면 이력과 작업 공간을 모두 되살린다. `.bak` 이 v1 이면 복구한 뒤 마이그레이션한다.
- 계약은 `WorkspaceRepositoryContract` 로 고정한다. 메모리 Fake(`InMemoryWorkspaceRepository`)와 `JsonStateRepository` 가 함께 통과한다. 마이그레이션은 `MigrationV1ToV2Test`, 부분 손상 · 두 포트 나눠 쓰기는 `JsonStateWorkspaceTest` 가 본다. 픽스처 `v1-history-only.json`(v0.4 가 쓴 파일) · `v1-sdd-example.json` · `v2.json` 은 지우지 않는다. 상위 버전 픽스처는 `v3-*.json` 이다.
- 시작할 때 복원(스냅샷으로 창을 띄우기 · 화면 밖 판정)과 저장 시점은 #179 의 범위다.

## 개정 (#179, 2026-11-28) — 시작 때 작업 공간 복원 · 작업 공간 저장 시점
- **시작 순서**(FR-RES-02). 복원할 배치를 모르고 p1 셸을 먼저 띄우면 그 셸을 버려야 하기 때문에 정했다.
  1. bootstrap(`Assembly.assemble`)은 state.json 을 **창을 만들기 전에** 읽는다.
     - 가상 스레드에서 `loadWorkspace` → `load` 를 차례로 부른다(문서 읽기는 한 번, #178).
     - 셸 탐지는 그동안 가상 스레드에서 미리 돈다(`ProfileCatalog` 는 한 번만 탐지한다). 셸 통합 설치도 지금처럼 나란히 돈다.
     - `StateActor` 를 만들기 직전에 결과를 기다린다. 로컬 파일이라 수 ms 다.
  2. 처음 상태는 `AppState.initial(빈 이력, RestoreWorkspaceUseCase.plan(snapshot))` 이다. snapshot 이 없으면(처음 실행 · v1 이력만 · 작업 공간을 버림) `Workspace.initial()` 이다. 저장소는 `Loading` 으로 시작한다.
  3. 불러온 이력(`HistoryLoaded` · `HistoryLoadFailed`)과 작업 공간 불러오기 알림(`WorkspaceLoaded` → `StoreProblem.WorkspaceLoaded`)은 **UI 가 이벤트를 구독한 뒤** `Assembly.startStore()` 가 넣는다.
     - 결과는 이미 손에 있다. 그래서 구독 전에 넣으면 불러오기 알림이 반드시 사라진다. 전에는 불러오기가 늦어 우연히 피하던 경합이다.
     - 그때까지의 방문은 지금처럼 `Loading(pending)` 에 쌓였다가 불러온 이력 위에 합쳐진다.
  4. UI 는 그 작업 공간을 그대로 그린다. **모든 탭의 창**을 만들고(활성 탭이 아닌 창은 숨김, ADR-0016 #177), 창마다 명세대로 셸을 띄운다. 기동은 창마다 백그라운드라 병렬이다(NFR-02).
     - 셸이 뜨거나 화면이 바뀔 때 키보드 포커스는 작업 공간의 포커스 창만 가져간다(`PaneController.Host.focused`). 여러 창이 차례로 뜨면서 저장된 포커스를 빼앗지 않게 하려는 것이다.
- **창 기동**(SDD §6.2). 명세에 경로가 있을 때의 규칙이다.
  - **Windows 경로**: 지금과 같다. 미리 확인하지 않고, `LaunchError.WorkingDirectoryNotFound` 가 오면 홈에서 다시 띄운다(ADR-0009).
  - **WSL 경로**: 기동 전에 `PlatformModule.directories().isDirectory(path, 1초)`(#53)로 확인한다. wsl.exe 는 없는 `--cd` 를 받아도 오류 없이 `/` 에서 뜨기 때문이다.
    - 없으면(false) 홈에서 띄운다.
    - 모르면(시간 초과 · 접근 불가) 확인 없이 그 경로로 띄운다. VM 이 깨어나는 동안 창을 오래 막지 않으려는 것이다.
    - 설정 시작 경로 · 트리에서 연 경로 · 다시 띄우기에도 같은 규칙을 쓴다. 기동 경로 하나(`SessionStarter.launch`)에서 판정한다.
  - 홈에서 띄웠으면 두 경우 모두 `StartNotice.MissingDirectory(경로)` 로 알린다. 전의 `StartDirMissing` 을 이 이름으로 바꿨다(알림 하나로 맞춤).
    - 문구는 "`<경로>` 이 없어 홈에서 열었다"다. 창 머리줄 경고(⚠ 툴팁)와 상태줄에 보인다.
  - 홈에서 떠 셸이 OSC 를 알리기 전에 끝난 창은 저장할 때 원래 명세 경로를 그대로 쓴다(`WorkspaceSnapshot.capture`). 홈으로 덮지 않는다.
  - **프로파일이 탐지 목록에 없으면** 지금의 `StartFailure.ChoiceRequired`(셸 선택 화면)다. 다른 창은 그대로 뜬다.
    - 사용자가 **같은 환경의 셸**을 고르면 저장된 경로에서 연다(`Attempt.Chosen.dir`). 다른 환경의 셸이면 그 셸의 시작 경로에서 연다. 다른 환경의 경로는 그 셸로 열 수 없기 때문이다.
- **저장 시점**(FR-RES-01)
  - 이력의 일정 조율을 `ThrottledSaver<T>`(application.service, 패키지 전용)로 뽑았다. 규칙은 위 #79 그대로다. 이력 · 작업 공간이 같은 규칙을 쓴다.
    - 첫 미저장 변경 후 1초.
    - 한 번에 하나. 저장 중에 기한이 오면 끝나자마자 다음 저장.
    - 종료 때 flush.
    - 파일에 있다고 아는 값 · 쓰는 중인 값과 같으면 쓰지 않는다.
    - 같은 실패는 한 번만 알린다.
    - 규칙 8 의 예외(단일 monitor · I/O 는 monitor 밖)도 이 클래스가 진다. `HistorySaver` 에는 불러오는 중인지 · 그동안 방문이 있었는지만 남는다.
  - `WorkspaceSaver` 는 같은 조율로 `saveWorkspace(WorkspaceSnapshot.capture(workspace, sessions, window))` 를 부른다.
    - 받는 이벤트: `LayoutChanged` · `SessionsChanged` · `SessionCwdChanged`, 그리고 `HistoryChanged`. 마지막 것은 불러오기가 끝났다는 신호다 — 불러오는 동안 바뀐 것을 그때 예약한다.
    - 받을 때마다 capture 를 다시 계산해 파일에 있는 값과 다를 때만 예약한다. 그래서 저장된 명세 그대로 셸이 뜨기만 한 것은 바뀐 것이 아니다.
    - `ReadOnly` 면 예약하지 않는다. `Loading` 중에는 예약해도 쓰지 않는다(쓸 수 있을 때만 쓰는 규칙은 이력과 같다).
    - 시작 때 읽은 snapshot 을 "파일에 있는 값"으로 안다. snapshot 이 없으면(처음 실행 · v1 이력만) 시작 상태의 capture 를 그 값으로 본다 — 다시 읽어도 같은 작업 공간으로 시작하기 때문이다. 그래서 불러오기만 하고 바꾸지 않으면 종료 때도 파일을 건드리지 않는다.
    - 창 상태(`window`)는 불러온 값(없으면 `WindowState.DEFAULT`)을 그대로 다시 쓴다. 창 상태를 따라가는 일(`WindowChanged`)은 #180 이 이 저장기에 붙인다.
  - 두 저장기가 각자 문서 전체를 쓴다(어댑터가 두 부분의 마지막 값을 들고 있다, #178). 둘이 같은 초에 바뀌면 1초에 두 번 쓸 수 있다. 받아들인 대가다.
- **종료 순서**(ADR-0011 · 0012): 상태 실행기 종료 → 이력 flush → 작업 공간 flush(둘이 합쳐 한 기한 2초) → 저장소 닫기.
  - 둘 중 하나라도 실패하면 저장소를 닫지 않는다(위 #79 와 같은 까닭).
  - 앱 종료는 창을 `ClosePaneUseCase` 로 닫지 않는다. 그래서 종료가 작업 공간을 빈 배치로 덮지 않는다.
- 범위 밖: 창 위치 · 크기 · 사이드바 상태의 복원과 화면 밖 판정(#180), 탭 막대(#181).
