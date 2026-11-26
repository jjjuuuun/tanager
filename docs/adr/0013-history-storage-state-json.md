# 0013. 이력 저장: state.json 의 history · 저장 시점 · 손상 처리

- 상태: 채택(#78, 2026-11-26) — 형식 · 원자적 쓰기 · `.bak` · 손상 · 상위 버전 · 잠금은 #78 에서 구현했다. 저장 시점(throttle) · 불러오기 전 방문 · 종료 시 저장은 #79 에서 구현한다.
- 날짜: 2026-11-26
- 관련: #78 · #79, FR-HIS-01 · FR-RES-01 · FR-RES-04(이력 부분), NFR-03, SDD §6.4 · §9.1 · §9.2 · §9.4, ADR-0009 · 0012

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
- **저장 시점**(#79 에서 구현): **첫 미저장 변경 후 1초**(throttle). 트레일링 디바운스는 변경이 계속 오면 저장이 계속 밀려 FR-RES-01 "1초 안"을 지키지 못한다. 저장은 I/O 가상 스레드에서 한 번에 하나만 돈다. 종료 때는 바로 flush 한다.
- **불러오기 전 방문**(#79 에서 구현): `Loading(pending)` 상태에서 쌓아 두었다가 load 결과 위에 다시 적용한다. Loading 중에는 저장하지 않는다.

## 결과
- FR-RES-01 · 04 의 이력 부분이 M3 에서 먼저 충족된다. M5 는 같은 adapter 가 `WorkspaceRepository` 도 구현해 같은 파일의 다른 키를 쓴다. 그때 "다른 최상위 키 보존"은 두 저장소가 한 문서를 나눠 쓰는 구조로 바뀐다.
- 계약은 `HistoryRepositoryContract` 로 고정한다. 메모리 Fake(`InMemoryHistoryRepository`)와 `JsonStateRepository` 가 함께 통과한다. 저장 거부 규칙(load 전 · `READ_ONLY` · load 실패 뒤 `save` → `ReadOnly`)도 계약에 있고, 구현마다 훅 `arrangeReadOnly()` · `arrangeLoadFailure()` 로 상황을 만든다(Json 은 v2 픽스처 · `state.json` 자리의 폴더). 손상 · 잠금 · 백업 · 형식은 `JsonStateRepositoryTest` 가 본다.
- `save` 는 블로킹 I/O 다. actor · EDT 에서 부르지 않는다(ADR-0012).
- POSIX(WSL · macOS)에서는 한 프로세스가 같은 파일에 연 채널 하나를 닫으면 그 프로세스의 잠금이 OS 수준에서 모두 풀린다. 한 프로세스에 저장소가 하나뿐이면 문제가 없다. 그래서 bootstrap 은 저장소를 하나만 만들고, **같은 프로세스에 같은 파일의 저장소 둘을 두는 것은 지원하지 않는다**. 같은 JVM 의 두 저장소 테스트는 JVM 잠금 표 수준만 검증하고, 프로세스 사이 잠금은 자식 JVM 이 `state.lock` 을 쥐는 테스트가 본다.
