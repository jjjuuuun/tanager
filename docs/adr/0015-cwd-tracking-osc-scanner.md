# 0015. cwd 추적 파이프라인: OSC 스캐너 위치와 CwdTracker

- 상태: 채택(#72, 2026-11-26)
- 날짜: 2026-11-26
- 관련: #72, FR-SES-03 · FR-HIS-01 · NFR-01 · NFR-05, ADR-0003 · 0007

## 맥락
ADR-0003 은 셸 통합 스크립트가 프롬프트마다 OSC 7(bash) · OSC 9;9(PowerShell · cmd)로 cwd 를 알리게 했다. M0 의 스캐너는 스파이크였다. M3 에서 실제 스캐너를 만들면서 다음을 정해야 한다.

1. 스캐너를 어느 계층에 둘지. PTY 읽기 스레드(세션마다 하나, ADR-0007)가 바이트를 `TerminalStream` 구독자에게 넘기고, 그중 하나가 cwd 를 뽑는다.
2. 시퀀스가 read 경계에서 잘리고, ConPTY 가 앞에 제어 시퀀스(`ESC[1t ESC[c ESC[?1004h ESC[?9001h`)를 붙인다. 큰 출력(50MB) 중에도 읽기 스레드를 늦추면 안 된다(NFR-01).
3. OSC 7 경로는 퍼센트 인코딩된 UTF-8 이다. 그런데 domain 은 `java.nio..` 를 쓰지 못한다(ArchUnit `domainIsPure`).

## 선택지
**스캐너 위치**
- (a) pty 어댑터가 OSC 를 뽑아 `TerminalStream` 에 cwd 채널을 둔다. 포트가 셸 통합을 알게 된다.
- (b) application 에 가변 스캐너를 둔다.
- (c) **domain 에 불변 스캐너를 두고, application `CwdTracker` 가 그 값 하나를 스레드 한정으로 든다.**

**UTF-8 해석**
- (a) **domain 에 `java.nio.charset.StandardCharsets` 만 허용하고, 디코딩한 문자열을 다시 인코딩해 원본과 비교(왕복 비교)해 엄격하게 해석한다.**
- (b) domain 에 작은 엄격 UTF-8 디코더를 직접 작성한다.

## 결정
- **(c)**. `domain.session.OscScanner` 는 불변 값이다. `feed(byte[])` 는 `Scan(next, reports)` 를 돌려준다. 스캐너 규칙(종료자 · 4KB · 취소 · 디코딩)을 Swing · 스레드 없이 바이트 픽스처(`src/test/resources/osc/`)와 jqwik 로 고정한다.
  - `ESC ]` 다음 `7;` 또는 `9;9;` 로 시작해 BEL 또는 ST(`ESC \`)로 끝나는 것만 모은다. 다른 OSC(제목 `0;` · `9;4;` · `8;;` 등)는 종료자까지 버린다.
  - 본문(`ESC ]` 와 종료자 사이)이 4096 바이트를 넘으면 버린다.
  - OSC 도중 CAN · SUB 는 그 OSC 를 취소한다. OSC 안의 ESC 뒤에 `\` 가 아닌 글자가 오면 취소하고, 그 ESC 는 새 이스케이프 시퀀스의 시작으로 본다(xterm 과 같다).
  - OSC 7 은 `file://host/경로` 만 받는다. 호스트는 비어 있을 수 있다. 퍼센트 디코딩 뒤 엄격 UTF-8 로 해석하고, 실패하면 버린다. OSC 9;9 는 따옴표가 있으면 벗기고 없으면 그대로 받는다(cmd 의 `$P` 는 따옴표가 없다).
  - 빠른 경로: 진행 중인 이스케이프 시퀀스가 없고 조각에 ESC 가 없으면 같은 결과 인스턴스를 돌려준다(복사 · 할당 없음).
  - 디코딩한 경로 · 호스트에 제어 문자(C0 · DEL)가 있으면 그 보고를 버린다. NUL · 줄바꿈은 어느 OS 에서도 경로가 아니고, `cat` 한 파일 같은 임의 출력이 이력을 더럽힐 수 있다.
  - cwd 가 아닌 OSC(제목 · 하이퍼링크 등)는 본문 버퍼를 만들지 않고 종료자까지 버린다.
  - `CwdReports.toPath(report, env)` 가 `WorkspacePath` 로 해석한다. OSC 7 은 WSL 에서만, OSC 9;9 는 Windows 에서만 받는다. 환경이 맞지 않으면 `PathError.NotAbsolute` 다.
- java-style 규칙 8 에 예외 한 줄을 더한다: "스트림 구독 콜백 안의 스레드 한정 파서 상태는 application 에 둘 수 있다". `CwdTracker` 는 세션마다 하나이고, 그 세션의 읽기 스레드에서만 스캐너 값을 바꿔 든다.
- **UTF-8 은 (a)**. ArchUnit `domainIsPure` 는 `java.nio..` 중 `java.nio.charset.StandardCharsets` 한 클래스만 허용한다. 직접 만든 디코더보다 검증된 JDK 디코더를 쓰는 편이 틀릴 곳이 적다.
- **중복 · 원격**(#77 CwdTracker)
  - 중복 제거 기준은 그 창이 **마지막으로 알린 경로**다. 시작 경로와 비교하면 시작 폴더가 기록되지 않는다.
  - OSC 7 은 세션의 첫 host 로 고정한다. ssh 원격 셸이 OSC 7 을 내도 로컬 이력을 더럽히지 않게 한다. OSC 9;9 는 구별할 수 없어 받아들인다.
    - 고정하는 것은 모드가 맞고 경로로 해석된 첫 OSC 7 의 host 다. 해석하지 못한 보고는 host 를 고정하지 않는다.
  - 판정은 `domain.session.LiveSessions.cwdReported` 가, 방문 기록은 `WorkHistory.recordVisit` 이 한다. `VisitRecorder` 가 둘을 `StateActor` 명령 하나에서 함께 적용해 `HistoryChanged` → `SessionCwdChanged` 순으로 낸다. `CwdTracker` 는 중복을 거르지 않는다.
- **구독 순서**: `SessionStarter` 가 `StartedSession` 을 돌려주기 전에 붙인다(ADR-0007 의 start 전 구독). 콜백 예외는 내부에서 삼켜 구독이 해제되지 않게 한다. 로그는 세션마다 한 번만 남기고 출력 내용은 남기지 않는다.
  - 순서: (1) 세션 등록(`SessionsChanged`) (2) 통합이 `Installed` 이고 모드가 `NONE` 이 아니면 `CwdTracker` 구독 (3) `onExit` 에 종료 등록(`SessionsChanged`). 종료는 그 창의 현재 세션 pid 가 같을 때만 적용한다 — 재시작 뒤 늦게 온 옛 셸의 종료가 새 세션을 끝난 것으로 만들지 않게.
  - 셸 통합이 `Unavailable` 이면 구독하지 않고 `StartNotice.IntegrationUnavailable` 을 준다. 시작 경로가 `At` 이면 그 경로를 한 번 기록한다(경로가 없어 홈으로 대신 띄웠으면 기록하지 않는다).
  - 통합 여부는 `IntegrationStatus` 가 프로세스에서 한 번 계산한 설치 결과를 기다려 쓴다(ADR-0014). start · restart 마다 `install()` 을 다시 부르지 않는다. 포트 계약을 어겨 예외가 나면 `Unavailable` 로 기억한다.

## 결과
- 스캐너는 픽스처 · 속성 테스트로 조각을 어디서 나눠도 같은 보고를 낸다는 것을 고정한다. ConPTY 앞머리 · 한글 경로(`local LC_ALL=C` 가 빠졌을 때 나오는 바이트 포함) 회귀를 domain 테스트가 잡는다.
- `TerminalStream` 포트는 셸 통합을 모른다. JediTerm 은 같은 바이트를 그대로 받는다.
- domain 의 순수성 규칙에 예외가 하나 생긴다(`StandardCharsets`). 다른 `java.nio` 클래스는 여전히 금지다(`ArchitectureRulesSelfTest` 가 검사).
- 큰 출력 중 지연은 #93 에서 측정한다.
