# 0012. 애플리케이션 상태: StateActor · 명령 함수 · 이벤트

- 상태: 채택(#76, 2026-11-26)
- 날짜: 2026-11-26
- 관련: #76, #97 · #99, SDD §4.2 · §5.2 · §5.7, FR-HIS-01, ADR-0002 · 0007 · 0009

## 맥락
M3 에서 공유 상태(작업 경로 이력 `WorkHistory`, 살아 있는 세션, 작업 공간)가 처음 생긴다. ADR-0009 는 StateActor · UiDispatcher 도입을 이때로 미뤘다.

- 상태를 바꾸는 쪽이 여럿이다: 세션마다 하나인 PTY 읽기 스레드(ADR-0007), EDT, I/O 스레드(저장).
- domain 은 락 · 스레드를 모른다(ArchUnit `domainIsPure`). 도메인 값은 불변이고, 연산은 새 값을 돌려준다.
- UI 는 이력이 바뀌면 트리를 통째로 다시 투영한다. 저장은 "바뀌었다"만 알면 된다.

함께 정해야 하는 도메인 쪽 결정이 둘 있다. 이 상태가 담는 값의 경계이기 때문이다.

1. history 트리 · layout · workspace 가 함께 쓰는 창 · 탭 ID 를 어느 컨텍스트에 둘지.
2. `WorkHistory` 의 항목 수 한도가 언제 성립하는지.

## 선택지
**상태 변경 직렬화**
- (a) `synchronized` 서비스. 락 범위를 잘못 잡으면 잃어버린 갱신이 생기고, 도메인 호출 도중 콜백이 락을 쥔 채 돈다.
- (b) **단일 스레드 실행기 + 순수 명령 함수.**
- (c) 명령을 sealed 타입으로 두고 큰 reducer 하나. 명령이 늘수록 switch 하나가 비대해진다.

**창 · 탭 ID 위치**
- (a) `session` 에 둔다. history 트리가 세션 잎을 만들려면 history → session 의존이 생기고, session 은 이미 history(`WorkspacePath` · `ShellEnvironment`)를 쓰므로 순환이 된다.
- (b) **`domain.shared` 에 둔다.**

## 결정
- **(b)**. `Command<R>` 는 `AppState → Transition(next, events, reply)` 순수 함수다.
  - 유스케이스마다 정적 팩터리(예: `HistoryCommands.recordVisit(...)`)로 두고 actor 없이 단위 테스트한다.
  - 실패는 응답 값(`Result`)으로 돌려준다. 예외는 버그에만 쓴다.
- `StateActor(AppState initial, Executor serial)`
  - `submit(command)` 은 응답 `CompletionStage` 를 바로 돌려주고, 일은 실행기에서 한다. 순서는 apply → 상태 교체 → 이벤트 발행 → 응답 완료다. 응답을 받은 쪽은 이벤트가 이미 나갔다고 믿어도 된다.
  - 명령이 예외를 던지면 상태를 그대로 두고 이벤트를 내지 않는다. 응답은 그 예외로 완료하고 `System.Logger` 로 남긴다. 다음 명령은 계속 받는다.
  - `snapshot()` 은 마지막 확정 `AppState` 를 volatile 로 읽는다. 읽기는 actor 를 거치지 않는다. `AppState` 가 불변이라 명령 도중의 중간 상태는 보이지 않는다. 트리 조회는 EDT 에서 이 스냅샷으로 순수 투영한다.
  - 상태를 바꾸지 않는 명령은 `current` 그대로와 빈 이벤트 목록을 돌려준다. 그러면 스냅샷은 같은 인스턴스로 남는다.
- **실행기**: 운영은 단일 **플랫폼** 데몬 스레드 `tanager-state`(`StateActor.serialExecutor()`). 명령에 블로킹 I/O 가 없어 가상이든 플랫폼이든 차이는 작다. 이름이 있는 고정 스레드라 스레드 덤프에서 찾기 쉽다. 테스트는 testFixtures `ManualExecutor`(큐에 쌓았다가 `runAll`)로 결정적으로 돈다.
- **이벤트**는 바뀐 값 위주로 적게 둔다. `AppEvent` 는 sealed 이고, M3 에서 늘려 갈 목록은 다음과 같다.
  - `HistoryChanged(WorkHistory)`(#76) · `SessionsChanged` · `SessionCwdChanged` · `EnvironmentsChanged` · `LayoutChanged` · `LastPaneClosed` · `StoreNotice`
  - SDD §5.2 의 `Changed<T>` 와 §5.7 의 세부 이벤트(`VisitRecorded` 등)는 소비자가 없어 두지 않는다. 도메인 연산은 새 값만 돌려주고, application 이 바뀐 스냅샷을 이벤트로 낸다(SDD 수정).
  - 이 모듈은 JPMS 가 아니라 다른 패키지에 걸친 sealed 계층을 만들 수 없다. 그래서 이벤트 합 타입은 application 의 `AppEvent` 하나로 둔다.
- **구독**
  - `StateActor.subscribe(listener)` 의 콜백은 실행기 스레드에서 발행 순서대로 불린다. 오래 막지 않는다.
  - 콜백이 던지면 그 구독만 해제하고 다른 구독자는 계속 받는다(ADR-0007 과 같은 규칙). 등록 목록은 `CopyOnWriteArrayList` 라 발행 도중 구독 · 해제해도 된다.
  - UI 는 port.in `AppEvents.subscribeOnUi` 로 받는다. 구현 `UiAppEvents` 는 `actor.subscribe(e -> dispatcher.dispatch(() -> listener.accept(e)))` 다.
  - port.out `UiDispatcher.dispatch(Runnable)` 는 ui 의 `SwingUiDispatcher`(`SwingUtilities.invokeLater`)가 구현한다. EDT 안에서 불려도 바로 돌리지 않아 넘긴 순서가 지켜진다. 테스트는 testFixtures `DirectUiDispatcher` 를 쓴다.
- **창 · 탭 ID 는 (b)** — `PaneId` · `TabId` 는 `domain.shared` 에 둔다(#99).
  - 컨텍스트 의존은 session → history 한 방향이다. history 트리의 세션 잎(`SessionLeaf`)은 session 타입을 import 하지 않고, 경로는 `Optional<WorkspacePath>` 로 받는다.
  - ArchUnit `domainPackagesAreFreeOfCycles` 가 domain 패키지 순환을 막는다.
- **이력 한도 불변식**(#97)
  - 항목 수 ≤ `max(한도, 고정 수 + 1)`. 고정 항목과 방금 방문한 항목은 한도를 넘어도 지우지 않는다.
  - 이 식은 `recordVisit` · `restore` **직후에만** 성립한다. `pin` · `hide` 는 항목을 지우지 않는다. 고정을 풀자마자 항목이 사라지지 않게 하려는 것이다. 다음 방문 때 다시 한도에 맞춘다.
  - 그래서 액터 밖의 소비자(저장 · 트리 투영)는 항목 수가 언제나 한도 이하라고 가정하지 않는다.

## 결과
- java-style 규칙 8("가변 상태는 adapter 와 StateActor 안에만")을 그대로 지킨다. StateActor 의 가변 필드는 상태 참조(volatile)와 구독 목록뿐이다.
- 상태 변경 규칙은 명령 함수 단위 테스트로, 직렬화 · 순서 · 예외 격리는 `StateActorTest` · `StateActorConcurrencyTest` 로 고정한다.
- 명령 하나가 오래 걸리면 뒤의 모든 명령과 이벤트가 밀린다. 명령에 I/O 를 넣지 않는다(저장은 `HistoryChanged` 를 받은 쪽이 I/O 실행기에서 한다).
- 구독자가 실행기 스레드에서 돈다. UI 콜백은 반드시 `AppEvents.subscribeOnUi` 로 받아 EDT 로 넘긴다.
- 조립(bootstrap 에서 `StateActor` · `SwingUiDispatcher` 를 만들어 넘기기)은 처음 쓰는 이슈(#77 CwdTracker · #79 이력 불러오기 · 저장)에서 한다.
