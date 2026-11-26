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
  - `sources` 는 지금 `shell` 만 쓰고, 읽을 때는 무시한다. M6 의 `claude-code` 는 값 추가라 버전이 그대로다.
- **모르는 최상위 키 보존**: load 때 읽은 최상위 키 가운데 `schemaVersion` · `history` 를 뺀 나머지를 adapter 가 들고 있다가 저장할 때 그대로 다시 쓴다. 디스크를 다시 읽지 않는다. 사람이 고치는 파일이 아니어서 config(ADR-0009, 저장 때 디스크를 다시 읽음)와 판단이 다르다. 항목 안의 모르는 필드는 보존하지 않는다(받아들인 한계).
- **항목 읽기**: 상대 경로 · 모르는 `env.kind` · `visitCount < 1` · 필수 필드 누락 · 불변식 위반(처음 > 마지막, 고정이면서 숨김)인 항목은 그것만 건너뛰고 `EntriesSkipped(n)` 으로 알린다. 대소문자만 다른 Windows 경로 같은 중복은 `WorkHistory.restore` 가 합친다.
- **뼈대 손상**: JSON 문법 오류, 최상위가 객체가 아님, `schemaVersion` 이 1 이상의 정수가 아님, `history` 가 객체가 아님, `history.entries` 가 배열이 아님. 이때는 파일 전체를 믿지 않는다.
  1. `state.json.bak` 이 정상이면 그것을 쓰고 `RecoveredFromBackup` 을 알린다.
  2. `.bak` 도 손상이거나 없으면 원본을 `state.json.corrupt-yyyyMMdd'T'HHmmss'Z'`(UTC)로 옮기고 빈 이력으로 시작한다(`CorruptMovedAside(파일명)`). 손상 파일은 덮어쓰지 않는다.
  3. 손상으로 판정한 파일은 그 실행의 `.bak` 으로 복사하지 않는다. 좋은 백업을 손상 파일로 덮지 않으려는 것이다.
- **읽기 전용**: 다음 경우 읽을 수 있는 만큼 읽고 `READ_ONLY` 로 연다. `save` 는 파일을 건드리지 않고 `ReadOnly` 오류다. 기록은 메모리에만 남는다.
  - `schemaVersion > 1`(`NewerSchema(v)`, SDD §9.4 다운그레이드 보호).
  - 다른 인스턴스가 `state.lock` 을 쥐고 있다(`LockedByOtherInstance`). load 에서 `FileChannel.tryLock` 으로 잡고 `close()` 에서 푼다. 같은 JVM 의 다른 채널이 쥔 경우(`OverlappingFileLockException`)도 같게 본다. 다른 인스턴스가 쓰는 손상 파일은 옆으로 옮기지 않는다.
  - load 전 · close 뒤의 `save` 도 `ReadOnly` 다. load 때 읽은 다른 최상위 키가 없어 `workspace` 를 지울 수 있기 때문이다.
  - 단일 인스턴스(기존 창 앞으로 가져오기)는 M5 에서 다시 본다.
- **원자적 쓰기**: `state.json.tmp` 에 쓴 뒤 `ATOMIC_MOVE` + `REPLACE_EXISTING` 으로 바꾼다. 실패하면 `Io` 오류이고 기존 파일은 그대로다.
- **`.bak` 시점**: **실행(저장소 인스턴스)마다 첫 저장 직전에 한 번** 그때의 `state.json` 을 복사한다. 기존 파일이 없으면 만들지 않는다. SDD §6.4 는 "교체 전마다"였지만, 1초마다 갱신되는 `.bak` 은 같은 실행 안의 논리 오류를 막지 못한다(SDD 수정). 백업을 만들지 못해도 저장은 계속한다. 백업이 없다고 새 방문을 잃으면 안 되기 때문이다.
- **저장 시점**(#79 에서 구현): **첫 미저장 변경 후 1초**(throttle). 트레일링 디바운스는 변경이 계속 오면 저장이 계속 밀려 FR-RES-01 "1초 안"을 지키지 못한다. 저장은 I/O 가상 스레드에서 한 번에 하나만 돈다. 종료 때는 바로 flush 한다.
- **불러오기 전 방문**(#79 에서 구현): `Loading(pending)` 상태에서 쌓아 두었다가 load 결과 위에 다시 적용한다. Loading 중에는 저장하지 않는다.

## 결과
- FR-RES-01 · 04 의 이력 부분이 M3 에서 먼저 충족된다. M5 는 같은 adapter 가 `WorkspaceRepository` 도 구현해 같은 파일의 다른 키를 쓴다. 그때 "다른 최상위 키 보존"은 두 저장소가 한 문서를 나눠 쓰는 구조로 바뀐다.
- 계약은 `HistoryRepositoryContract` 로 고정한다. 메모리 Fake(`InMemoryHistoryRepository`)와 `JsonStateRepository` 가 함께 통과한다. 손상 · 잠금 · 백업 · 형식은 `JsonStateRepositoryTest` 가 본다.
- `save` 는 블로킹 I/O 다. actor · EDT 에서 부르지 않는다(ADR-0012).
- POSIX(WSL · macOS)에서는 한 프로세스가 같은 파일에 연 채널 하나를 닫으면 그 프로세스의 잠금이 OS 수준에서 모두 풀린다. 한 프로세스에 저장소가 하나뿐이면 문제가 없다. 그래서 bootstrap 은 저장소를 하나만 만든다.
