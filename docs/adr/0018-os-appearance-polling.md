# 0018. OS 다크/라이트 변경 감지: reg.exe 주기 읽기

- 상태: 채택(#184, 2026-11-28)
- 날짜: 2026-11-28
- 관련: #184 · #185 · #152 · #182, FR-UI-01 · FR-UI-04, SDD §5.7 · §6.7, 리스크 R10, ADR-0002 · 0013

## 맥락
FR-UI-01 은 테마 모드를 시스템(기본) · 다크 · 라이트로 둔다. 시스템 모드는 OS 설정을 따르고, OS 에서 바꾸면 재시작 없이 바로 반영해야 한다.

#152 까지 `SystemAppearance` 는 '애니메이션 효과'만 담았고 시작 때 한 번 읽었다. 읽는 방법은 `reg.exe query` 다. platform/windows 는 네이티브 호출(JNA)에 기대지 않는다는 원칙(platform.md)을 지키려는 것이다.

SDD §6.7 은 "`PlatformModule.appearance()` 가 OS 변경을 구독한다"고 적고 레지스트리 변경 감지를 확인할 일(✱, R10)로 남겼다. R10 의 대안은 "창 포커스 때마다 다시 읽기"였다.

정할 것:
1. OS 값이 바뀐 것을 어떻게 알지.
2. 알림을 어디에 두고 UI 에 어떻게 넘길지.
3. 설정(`appearance.theme` · `appearance.animations`)과 OS 값을 어떻게 합칠지.

## 선택지
**변경 감지**
1. JNA `RegNotifyChangeKeyValue` — 바뀌는 즉시 안다. 그러나 platform/windows 가 네이티브에 의존하게 되어 기존 원칙을 깬다. 기다리는 스레드를 따로 두고 종료 때 풀어야 한다.
2. FFM(Java 25 정식 API)으로 같은 함수를 부른다 — 라이브러리는 늘지 않는다. 그러나 이벤트 핸들 · 대기 스레드 · 키 핸들의 수명과 종료 처리가 늘고, `--enable-native-access` 범위도 넓어진다. Windows 에서만 시험할 수 있다.
3. **주기 읽기** — application 이 `PlatformModule.appearance()` 를 일정 간격으로 다시 부르고, 창이 활성화될 때도 부른다. `reg.exe` 한 번은 수십 ms 이고, 값 두 개를 읽어도 5초 간격이면 부하가 작다. 늦어야 5초다. 창으로 돌아오는 순간(설정 앱에서 바꾸고 Tanager 를 누를 때)에는 활성화 읽기로 바로 반영된다.

**알림 위치**
1. `AppEvent.AppearanceChanged` 를 더해 StateActor 로 낸다 — 저장 상태가 아닌 값이 StateActor 를 지난다(SDD §5.7 위반). 같은 시기에 이력 이슈가 `AppEvent` 를 고쳐 파일이 겹친다.
2. **별도 유스케이스 `ApplyThemeUseCase`** — `current()` · `subscribe()` · `refresh()`. 리소스 표시(`WatchResourcesUseCase`, #187)와 같은 모양이다.

## 결정
- 감지는 **주기 읽기**(3)다.
  - application 의 `AppearanceWatcher` 가 구독이 하나라도 있는 동안 `Scheduler` 로 **5초**마다 다시 읽는다. UI 가 창 활성화 때 `refresh()` 를 부르면 그때도 읽는다.
  - 읽기는 I/O 실행기(운영은 가상 스레드)에서 **한 번에 하나만** 돈다. 읽는 도중 온 요청은 끝난 뒤 한 번으로 모은다. 아직 시작하지 않은 읽기가 있으면 새 요청은 그 읽기가 맡는다.
  - 구독이 모두 끊기면 주기 예약을 거둔다.
  - 시작 때는 `readNow()` 로 부른 스레드(main)에서 한 번 읽는다. 창을 만들기 전 값이 정해져 있어야 한다.
- Windows 는 `reg.exe query` 를 두 번 실행한다(`AppsUseLightTheme` · `MinAnimate`). 테마는 `0x0` = 다크, `0x1` = 라이트이고 읽지 못하면 empty 다. 애니메이션은 읽지 못하면 켠다.
- 포트: `SystemAppearance(Optional<Theme> theme, boolean animations)`. `PlatformModule` 에는 구독 메서드를 더하지 않는다. 변경 알림은 OS 별로 다르게 만들 일이 아니다. macOS(M7)도 `appearance()` 만 채우면 된다.
- 합치기는 순수 함수 `ThemeResolver` 가 한다.
  - 테마: SYSTEM 은 OS 값이고, 읽지 못하면 다크(M2 부터의 기본)다. DARK · LIGHT 는 OS 와 상관없다.
  - 움직임: AUTO 는 OS 값이고, ON · OFF 는 강제다.
- 알림은 `ApplyThemeUseCase`(2)로 한다.
  - 설정은 `SettingsChanged` 를 구독해 받고, OS 값과 합친 `ResolvedAppearance` 가 **바뀔 때만** 알린다.
  - 알림은 `UiDispatcher` 로 EDT 에 넘긴다. 넘긴 뒤 끊은 구독에는 보내지 않는다.
  - StateActor 를 거치지 않는다.
- 상태(설정 · OS 값 · 구독 · 예약 · 읽기 진행)는 `HistorySaver` 처럼 단일 monitor 로 지켜 application.service 에 둔다(java-style 규칙 8 의 예외, ADR-0013 과 같은 근거: 출력 포트의 일정 조율).

## 결과
- 네이티브 의존 없이 FR-UI-01 의 "재시작 없이 반영"을 만족한다. 늦어야 5초이고 창으로 돌아오면 바로다. R10 은 이 방식으로 닫는다.
- 감수하는 것: 구독 중에는 5초마다 `reg.exe` 를 두 번 띄운다. 바뀌지 않았어도 띄운다. 프로세스 생성 비용은 Windows 에서 수십 ms 다. 부하가 문제가 되면 간격을 늘리거나 FFM(2)으로 바꾼다. 포트는 그대로 두고 application 의 주기만 바꾸면 된다.
- 화면 적용(FlatLaf 테마 교체 · 터미널 팔레트 · 창 활성화 때 `refresh()` 호출)은 #185 가 한다. 그때까지 UI 는 시작 때 `current()` 의 움직임만 쓰고 다크 그대로다. 구독자가 없으니 주기 읽기도 돌지 않는다.
- SDD §6.7 의 "`appearance()` 가 OS 변경을 구독한다 · `AppearanceChanged`" 는 이 결정으로 바꾼다.
