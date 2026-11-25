# 0011. JediTerm 연결(TerminalStream 브리지 · 세션마다 새 위젯 · 번들 Font 객체)과 창 종료 흐름

- 상태: 채택(#46, 2026-11-26)
- 날짜: 2026-11-25
- 관련: #46, FR-SES-04 · FR-UI-03 · FR-UI-06 · NFR-02, ADR-0001 · 0006 · 0007 · 0009 · 0010(개정)

## 맥락
#46 에서 처음으로 실제 셸이 화면에 뜬다. 다음 네 가지는 이후 분할(M4) · 복원(M5) · 테마 전환(M5)이 모두 기대게 되어 나중에 바꾸기 어렵다.

1. push 포트(`TerminalStream`, ADR-0007)를 pull 인터페이스(JediTerm `TtyConnector.read(char[])`)로 바꾸는 위치와 UTF-8 디코딩 위치
2. 셸이 끝나고 다시 띄울 때 JediTerm 위젯을 재사용할지
3. 터미널 글꼴을 이름으로 찾을지, 번들에서 만든 `Font` 객체를 직접 쓸지(ADR-0010 이 #46 으로 미룸)
4. 종료 안내 · Esc · 창 닫기 · 앱 종료 순서

JediTerm 3.76 소스에서 확인한 사실:
- `JediEmulator` 는 CSI `c`(DA1)에 `ESC[?6c` 로 답한다(`JediEmulator.java:485`, `CharUtils.VT102_RESPONSE`). 응답과 키 입력 · 붙여넣기는 모두 `TerminalStarter.sendBytes` → 위젯의 단일 스레드 executor 에서 `TtyConnector.write` 로 간다. EDT 에서 쓰지 않는다.
- `JediTermWidget.start()` 는 시작할 때마다 `addCustomKeyListener(terminalKeyListener)` 를 다시 부른다(`JediTermWidget.java:395`). 끝난 위젯에 새 커넥터를 붙여 다시 `start()` 하면 키 리스너가 두 번 붙어 **글자가 두 번 입력된다**.
- 에뮬레이터 루프가 끝나면(read 가 -1) 위젯이 `TtyConnector.close()` 를 부른다. `JediTermWidget.close()` 는 executor 를 종료하므로 그 위젯은 다시 쓸 수 없다.
- `TerminalPanel` 은 `getTerminalFont()` 로 받은 `Font` 객체를 그대로 쓰고, IME 조합 중 글자도 그 객체(`myNormalFont`)로 그린다. 굵게는 `deriveFont(BOLD)` 로 만든다(합성 굵게). `getFontToDisplay` 는 protected 라 재정의할 수 있다.
- `useAntialiasing()=true` 는 `VALUE_TEXT_ANTIALIAS_ON`(그레이스케일)이다.
- ConPTY 는 크기 변경 직후 화면 전체를 다시 보낸다. JediTerm 은 이를 위해 `TtyConnectorResizeStrategy.POSTPONED`(500ms 합치기)를 둔다.

## 선택지
**브리지**: (a) 읽기 스레드 콜백에서 바로 디코딩해 char 큐에 넣는다. (b) **byte[] 조각을 그대로 큐에 넣고, JediTerm 읽기 스레드(`read`)가 디코딩한다.** (b) 는 콜백이 짧아 셸 출력이 덜 막히고, 디코더 상태를 한 스레드만 만진다.

**재시작**: (a) 위젯 재사용. 스크롤백이 남지만 위의 키 리스너 중복 때문에 JediTerm 내부를 우회해야 한다. (b) **세션마다 새 위젯**. 끝난 위젯은 `close()` 하고 버린다. 이전 출력은 사라진다.

**글꼴**: (a) `new Font("D2Coding", …)`. 시스템에 다른 버전의 D2Coding 이 설치돼 있으면 그쪽이 쓰인다(`registerFont` 가 false). (b) **`Font.createFont` 로 만든 Regular · Bold 객체를 `deriveFont(14f)` 해서 직접 쓴다.** 설치본과 섞이지 않는다.

## 결정
- **브리지 `StreamTtyConnector`(ui.terminal)**: 만들 때 `subscribe` 하고 `onExit` 에 EOF 표식을 건다. 그다음 위젯에 붙이고, 마지막에 `stream.start()` 를 부른다(ADR-0007 의 순서). 큐는 크기 제한이 있다(조각 1024개). 꽉 차면 읽기 스레드가 기다린다(배압). `close()` 는 구독을 끊고 큐를 비운 뒤 EOF 를 넣고 `stream.close()` 를 부른다. 디코딩은 `Utf8ChunkDecoder`(순수 함수)가 한다. 조각 경계에 걸친 멀티바이트는 다음 조각까지 들고 있고, 잘못된 바이트와 EOF 에 남은 조각은 U+FFFD 로 바꾼다.
- `write` 는 JediTerm executor 에서만 불린다. 우리 코드는 `TerminalStream.write` 를 EDT 에서 직접 부르지 않는다.
- 크기 변경은 `POSTPONED` 다. 0 이하 크기는 1 로 맞춘다(`TerminalSize` 불변식).
- **세션마다 새 `JediTermWidget`**. 재시작하면 이전 화면이 사라진다. 스크롤백 보존은 위젯 재사용 우회가 필요해 미룬다(JediTerm 을 올릴 때 다시 본다).
- **글꼴은 번들 `Font` 객체를 직접 쓴다(ADR-0010 개정)**. `TerminalFonts.registerBundled()` 는 `TerminalFontSet(regular, bold)` 을 돌려준다. 굵게는 `TerminalPanel.getFontToDisplay` 를 재정의해 번들 Bold 를 쓴다. JVM 등록(`registerFont`)은 이후 설정 화면에서 이름을 보여 주려고 남긴다. 등록에 실패하면 논리 글꼴 `Monospaced` 로 띄우고 상태줄에 알린다. 논리 글꼴은 한글 대체가 있어 조합 중 글자도 그려진다. 글꼴 하위 시스템의 `InternalError` 는 `VirtualMachineError` 의 하위이지만 삼켜서 `Err` 로 바꾼다.
- 설정: D2Coding 14pt, 줄 간격 1.2, 그레이스케일 AA, type-ahead 끔(ConPTY 의 다시 그리기와 다툰다), 종 소리 끔. 색은 `ui.theme` 의 `JediTermColors` 가 UIManager `Tanager.term.*` 에서 만든다(ArchUnit 색 규칙).
- **종료 흐름**
  - 셸이 끝나면 터미널 아래쪽에 띠를 띄운다: "종료 코드 N · Enter 다시 시작 · Esc 닫기". 마지막 출력은 가리지 않는다.
  - Enter 는 `restart` 다. Esc 는 창 닫기이고, 마지막 창이면 **확인 없이 앱을 끝낸다**. 셸이 이미 끝나 잃을 것이 없고, 레이아웃 복원은 M5 가 맡는다. 이슈 #46 본문의 "앱 종료 확인"을 이렇게 바꾼다.
  - 창 닫기(X)도 M2 에서는 확인하지 않는다. 여러 창이 생기는 M4 에서 "실행 중 N개" 확인을 다시 정한다.
  - 앱 종료 순서: 모든 창 `stream.close()` → 창 `dispose()`(바로 사라진다) → 가상 스레드에서 모든 `onExit` 를 최대 2초 기다림 → `System.exit(0)`. 기다리는 동안 pty 어댑터가 남은 자손을 정리한다.
- 창 상태 기계(`PaneState` · `PaneEvent` · `PaneEffect`)는 Swing 없는 순수 함수로 둔다. Swing 쪽(`PaneController`)은 효과만 실행한다.

## 결과
- JediTerm 의 DA1 응답으로 ConPTY 3초 지연(ADR-0007)이 없어진다. Windows 태그 통합 테스트로 확인한다: 첫 프롬프트 1.5초 이내, `exit` 뒤 종료 1초 이내.
- 재시작하면 이전 출력이 사라진다. 사용자가 불편해하면 위젯 재사용을 다시 검토한다.
- 시스템에 D2Coding 이 설치돼 있어도 번들 1.4.0 이 쓰인다.
- UI 는 계속 pty4j 를 모른다. Windows 통합 테스트는 모든 모듈을 아는 bootstrap 모듈에 둔다.
- 구현에서 확인한 것(#46, Windows 11)
  - DA1 지연이 사라졌다. 실제 PowerShell 을 띄워 프롬프트가 보이기까지 347~412ms, `exit 2` 뒤 종료까지 131~143ms(PowerShellSmokeTest, 4회 반복 통과).
  - 패키지된 Tanager.exe 가 첫 WSL 배포판의 bash 를 띄운다.
    - 제목 표시줄 탭, "bash · ~" 머리줄, 둥근 창 · 포커스 테두리, 상태줄 "WSL · Ubuntu-24.04 · bash" 를 캡처로 확인했다.
    - 창을 닫으면 0.3초 안에 프로세스가 남지 않는다.
  - JediTerm 3.76 에서 확인한 것
    - 커서 색 설정이 없다(반전으로 그린다). 그래서 `JediTermColors.cursor()` 는 아직 쓰지 않는다.
    - 기본 글자색은 사용 중단된 `getDefaultStyle()` 로 만들어진다. 재정의하지 않으면 ansi0(어두운 색)이 된다.
    - 번들 Bold 는 `TerminalPanel.getFontToDisplay` 재정의로 쓴다(`createTerminalPanel` 이 상위 생성자 안에서 불리므로 글꼴은 설정에서 꺼낸다).
  - `setTtyConnector` 가 초기 크기 동기화를 한 번 하므로, Connect 뒤 따로 resize 하지 않는다.
  - 남은 한계: 백그라운드 `start` 가 계약을 어기고 예외를 던지면 창이 "시작하는 중"에 머문다.
